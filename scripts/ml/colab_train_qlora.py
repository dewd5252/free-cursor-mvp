#!/usr/bin/env python3
"""QLoRA training script for Free Cursor single-step action JSON task."""

from __future__ import annotations

import argparse
import json
import random
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import torch
from datasets import Dataset, load_dataset
from peft import LoraConfig, prepare_model_for_kbit_training
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    TrainingArguments,
)
from trl import SFTTrainer

SYSTEM_PROMPT = "You are Free Cursor. Return valid JSON only."
ACTION_KEYS = {
    "action",
    "target_id",
    "text",
    "direction",
    "start_id",
    "end_id",
    "app_name",
    "package_name",
    "requires_cursor",
    "execution_mode",
    "confidence",
    "reason",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train Qwen2.5-0.5B with QLoRA for Free Cursor")
    parser.add_argument("--model-id", default="Qwen/Qwen2.5-0.5B-Instruct")
    parser.add_argument("--train", default="/content/dataset/splits/train.jsonl")
    parser.add_argument("--val", default="/content/dataset/splits/val.jsonl")
    parser.add_argument("--test", default="/content/dataset/splits/test.jsonl")
    parser.add_argument("--output-dir", default="/content/free_cursor_model")
    parser.add_argument("--lora-output", default="/content/drive/MyDrive/free_cursor_lora_weights")
    parser.add_argument("--report-output", default="/content/drive/MyDrive/free_cursor_eval_report.json")
    parser.add_argument("--max-seq-length", type=int, default=1024)
    parser.add_argument("--epochs", type=float, default=1.0)
    parser.add_argument("--per-device-batch", type=int, default=4)
    parser.add_argument("--grad-accum", type=int, default=4)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--logging-steps", type=int, default=100)
    parser.add_argument("--save-steps", type=int, default=500)
    parser.add_argument("--eval-steps", type=int, default=500)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--max-eval-samples", type=int, default=1000)
    parser.add_argument("--nodes-char-budget", type=int, default=5000)
    return parser.parse_args()


def compact_nodes(nodes: list[dict[str, Any]], char_budget: int) -> str:
    minimal_nodes = []
    current_size = 2

    for node in nodes:
        compact = {
            "id": node.get("id"),
            "role": node.get("role"),
            "text": node.get("text"),
            "hint": node.get("hint"),
            "clickable": node.get("clickable"),
            "enabled": node.get("enabled"),
            "editable": node.get("editable"),
            "bounds": node.get("bounds"),
            "actions": node.get("actions"),
        }
        serialized = json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
        if current_size + len(serialized) + 1 > char_budget:
            break
        minimal_nodes.append(compact)
        current_size += len(serialized) + 1

    return json.dumps(minimal_nodes, ensure_ascii=False, separators=(",", ":"))


def record_to_chatml(record: dict[str, Any], nodes_char_budget: int) -> str:
    command = record.get("user_command_raw", "")
    nodes = record.get("screen_nodes", [])
    allowed_actions = record.get("allowed_actions", [])
    target = record.get("target", {})

    nodes_json = compact_nodes(nodes, nodes_char_budget)
    target_json = json.dumps(target, ensure_ascii=False, separators=(",", ":"))
    allowed_json = json.dumps(allowed_actions, ensure_ascii=False, separators=(",", ":"))

    return (
        "<|im_start|>system\n"
        f"{SYSTEM_PROMPT}"
        "<|im_end|>\n"
        "<|im_start|>user\n"
        f"Command: {command}\n"
        f"AllowedActions: {allowed_json}\n"
        f"Nodes: {nodes_json}"
        "<|im_end|>\n"
        "<|im_start|>assistant\n"
        f"{target_json}"
        "<|im_end|>"
    )


def preprocess_dataset(ds: Dataset, nodes_char_budget: int) -> Dataset:
    def _map_fn(batch: dict[str, list[Any]]) -> dict[str, list[str]]:
        texts = []
        for i in range(len(batch["sample_id"])):
            record = {k: batch[k][i] for k in batch.keys()}
            texts.append(record_to_chatml(record, nodes_char_budget))
        return {"text": texts}

    ds = ds.filter(lambda x: isinstance(x.get("target"), dict) and bool(x["target"].get("action")))
    ds = ds.map(_map_fn, batched=True, remove_columns=ds.column_names)
    return ds


def extract_json(text: str) -> dict[str, Any] | None:
    text = text.strip()
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            return obj
    except Exception:
        pass

    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        snippet = text[start : end + 1]
        try:
            obj = json.loads(snippet)
            if isinstance(obj, dict):
                return obj
        except Exception:
            return None
    return None


def normalize_json(obj: dict[str, Any]) -> dict[str, Any]:
    return {k: obj.get(k) for k in ACTION_KEYS}


@dataclass
class EvalStats:
    total: int = 0
    json_valid: int = 0
    action_match: int = 0
    launch_app_exact: int = 0
    launch_app_total: int = 0
    noop_pred: int = 0
    noop_true_positive: int = 0

    def as_dict(self) -> dict[str, Any]:
        def pct(a: int, b: int) -> float:
            return (a / b) * 100 if b else 0.0

        noop_precision = pct(self.noop_true_positive, self.noop_pred)
        return {
            "total": self.total,
            "json_valid_rate": pct(self.json_valid, self.total),
            "action_accuracy": pct(self.action_match, self.total),
            "launch_app_exact": pct(self.launch_app_exact, self.launch_app_total),
            "noop_precision": noop_precision,
            "acceptance": {
                "json_valid_ge_98": pct(self.json_valid, self.total) >= 98.0,
                "action_accuracy_ge_80": pct(self.action_match, self.total) >= 80.0,
                "launch_app_exact_ge_90": pct(self.launch_app_exact, self.launch_app_total) >= 90.0,
                "noop_precision_ge_85": noop_precision >= 85.0,
            },
        }


def evaluate_model(
    model: AutoModelForCausalLM,
    tokenizer: AutoTokenizer,
    test_records: list[dict[str, Any]],
    max_seq_length: int,
) -> dict[str, Any]:
    stats = EvalStats()

    for record in test_records:
        prompt = (
            "<|im_start|>system\n"
            f"{SYSTEM_PROMPT}"
            "<|im_end|>\n"
            "<|im_start|>user\n"
            f"Command: {record.get('user_command_raw','')}\n"
            f"AllowedActions: {json.dumps(record.get('allowed_actions', []), ensure_ascii=False)}\n"
            f"Nodes: {compact_nodes(record.get('screen_nodes', []), 3000)}"
            "<|im_end|>\n"
            "<|im_start|>assistant\n"
        )

        inputs = tokenizer(
            prompt,
            return_tensors="pt",
            truncation=True,
            max_length=max_seq_length,
        ).to(model.device)

        with torch.no_grad():
            output_ids = model.generate(
                **inputs,
                max_new_tokens=220,
                do_sample=False,
                temperature=0.0,
                pad_token_id=tokenizer.pad_token_id,
                eos_token_id=tokenizer.eos_token_id,
            )

        generated = tokenizer.decode(
            output_ids[0][inputs["input_ids"].shape[-1] :],
            skip_special_tokens=True,
        )

        pred_json = extract_json(generated)
        gold_json = record.get("target", {})

        stats.total += 1

        if pred_json is None:
            continue

        stats.json_valid += 1

        pred = normalize_json(pred_json)
        gold = normalize_json(gold_json)

        if pred.get("action") == gold.get("action"):
            stats.action_match += 1

        if gold.get("action") == "launch_app":
            stats.launch_app_total += 1
            if (
                pred.get("action") == "launch_app"
                and pred.get("app_name") == gold.get("app_name")
                and pred.get("package_name") == gold.get("package_name")
            ):
                stats.launch_app_exact += 1

        if pred.get("action") == "noop":
            stats.noop_pred += 1
            if gold.get("action") == "noop":
                stats.noop_true_positive += 1

    return stats.as_dict()


def main() -> None:
    args = parse_args()

    random.seed(args.seed)
    torch.manual_seed(args.seed)

    raw_ds = load_dataset(
        "json",
        data_files={
            "train": args.train,
            "val": args.val,
            "test": args.test,
        },
    )

    train_ds = preprocess_dataset(raw_ds["train"], args.nodes_char_budget)
    val_ds = preprocess_dataset(raw_ds["val"], args.nodes_char_budget)

    tokenizer = AutoTokenizer.from_pretrained(args.model_id, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_use_double_quant=True,
        bnb_4bit_compute_dtype=torch.float16,
    )

    model = AutoModelForCausalLM.from_pretrained(
        args.model_id,
        quantization_config=bnb_config,
        device_map="auto",
        trust_remote_code=True,
    )
    model = prepare_model_for_kbit_training(model)

    lora_config = LoraConfig(
        r=32,
        lora_alpha=64,
        target_modules=[
            "q_proj",
            "k_proj",
            "v_proj",
            "o_proj",
            "gate_proj",
            "up_proj",
            "down_proj",
        ],
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
    )

    training_args = TrainingArguments(
        output_dir=args.output_dir,
        per_device_train_batch_size=args.per_device_batch,
        per_device_eval_batch_size=args.per_device_batch,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        num_train_epochs=args.epochs,
        logging_steps=args.logging_steps,
        save_steps=args.save_steps,
        eval_steps=args.eval_steps,
        evaluation_strategy="steps",
        save_strategy="steps",
        fp16=True,
        bf16=False,
        optim="paged_adamw_8bit",
        warmup_ratio=0.03,
        lr_scheduler_type="cosine",
        report_to="none",
        seed=args.seed,
    )

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        args=training_args,
        peft_config=lora_config,
        dataset_text_field="text",
        max_seq_length=args.max_seq_length,
        packing=False,
    )

    trainer.train()

    Path(args.lora_output).mkdir(parents=True, exist_ok=True)
    trainer.model.save_pretrained(args.lora_output)
    tokenizer.save_pretrained(args.lora_output)

    test_records = raw_ds["test"].shuffle(seed=args.seed).select(
        range(min(args.max_eval_samples, len(raw_ds["test"]))),
    )
    eval_report = evaluate_model(
        model=trainer.model,
        tokenizer=tokenizer,
        test_records=[test_records[i] for i in range(len(test_records))],
        max_seq_length=args.max_seq_length,
    )

    report_path = Path(args.report_output)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(eval_report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps({
        "status": "ok",
        "lora_output": args.lora_output,
        "eval_report": eval_report,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
