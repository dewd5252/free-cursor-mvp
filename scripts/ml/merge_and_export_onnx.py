#!/usr/bin/env python3
"""Merge LoRA weights with Qwen base model then export to ONNX text-generation-with-past."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import torch
from peft import AutoPeftModelForCausalLM
from transformers import AutoModelForCausalLM, AutoTokenizer


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Merge LoRA and export ONNX bundle")
    parser.add_argument("--base-model", default="Qwen/Qwen2.5-0.5B-Instruct")
    parser.add_argument("--lora-dir", default="/content/drive/MyDrive/free_cursor_lora_weights")
    parser.add_argument("--merged-dir", default="/content/free_cursor_merged")
    parser.add_argument("--onnx-dir", default="/content/free_cursor_onnx")
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument("--max-prompt-len", type=int, default=1024)
    parser.add_argument("--max-new-tokens", type=int, default=220)
    return parser.parse_args()


def export_with_optimum(merged_dir: str, onnx_dir: str, opset: int) -> None:
    from optimum.exporters.onnx import main_export

    Path(onnx_dir).mkdir(parents=True, exist_ok=True)

    main_export(
        model_name_or_path=merged_dir,
        output=onnx_dir,
        task="text-generation-with-past",
        opset=opset,
        trust_remote_code=True,
    )


def smoke_generation(merged_dir: str) -> dict[str, str]:
    tokenizer = AutoTokenizer.from_pretrained(merged_dir, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        merged_dir,
        torch_dtype=torch.float16,
        device_map="auto",
        trust_remote_code=True,
    )

    prompts = [
        "<|im_start|>system\nYou are Free Cursor. Return valid JSON only.<|im_end|>\n<|im_start|>user\nCommand: افتح واتساب\nAllowedActions: [\"launch_app\",\"noop\"]\nNodes: []<|im_end|>\n<|im_start|>assistant\n",
        "<|im_start|>system\nYou are Free Cursor. Return valid JSON only.<|im_end|>\n<|im_start|>user\nCommand: ارجع\nAllowedActions: [\"back\",\"noop\"]\nNodes: []<|im_end|>\n<|im_start|>assistant\n",
        "<|im_start|>system\nYou are Free Cursor. Return valid JSON only.<|im_end|>\n<|im_start|>user\nCommand: tap Search\nAllowedActions: [\"click\",\"noop\"]\nNodes: [{\"id\":1,\"role\":\"button\",\"text\":\"Search\"}]<|im_end|>\n<|im_start|>assistant\n",
    ]

    outputs: dict[str, str] = {}

    for i, prompt in enumerate(prompts, start=1):
        inputs = tokenizer(prompt, return_tensors="pt", truncation=True, max_length=1024).to(model.device)
        with torch.no_grad():
            gen = model.generate(
                **inputs,
                max_new_tokens=220,
                do_sample=False,
                temperature=0.0,
                pad_token_id=tokenizer.pad_token_id,
                eos_token_id=tokenizer.eos_token_id,
            )
        text = tokenizer.decode(gen[0][inputs["input_ids"].shape[-1] :], skip_special_tokens=True)
        outputs[f"prompt_{i}"] = text

    return outputs


def main() -> None:
    args = parse_args()

    Path(args.merged_dir).mkdir(parents=True, exist_ok=True)
    Path(args.onnx_dir).mkdir(parents=True, exist_ok=True)

    peft_model = AutoPeftModelForCausalLM.from_pretrained(
        args.lora_dir,
        torch_dtype=torch.float16,
        device_map="auto",
        trust_remote_code=True,
    )

    merged_model = peft_model.merge_and_unload()
    tokenizer = AutoTokenizer.from_pretrained(args.lora_dir, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    merged_model.save_pretrained(args.merged_dir)
    tokenizer.save_pretrained(args.merged_dir)

    export_with_optimum(args.merged_dir, args.onnx_dir, args.opset)

    for file_name in ["tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "generation_config.json"]:
        src = Path(args.merged_dir) / file_name
        if src.exists():
            shutil.copy2(src, Path(args.onnx_dir) / file_name)

    smoke = smoke_generation(args.merged_dir)
    report = {
        "status": "ok",
        "merged_dir": args.merged_dir,
        "onnx_dir": args.onnx_dir,
        "smoke_outputs": smoke,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
