# Free Cursor QLoRA + ONNX Pipeline

This folder contains executable scripts for the full training/export path:

1. Package local dataset
2. Train Qwen2.5-0.5B-Instruct with QLoRA on Colab T4
3. Evaluate key JSON/action metrics
4. Merge LoRA and export ONNX (`text-generation-with-past`)

## Open Directly In Colab

- Notebook file: `scripts/ml/free_cursor_train_qlora_colab.ipynb`
- Direct link:
  - `https://colab.research.google.com/github/dewd5252/free-cursor-mvp/blob/main/scripts/ml/free_cursor_train_qlora_colab.ipynb`

## 1) Package dataset locally

```bash
bash scripts/ml/package_dataset.sh dataset free_cursor_dataset.tar.gz
```

Upload both files to Google Drive:

- `free_cursor_dataset.tar.gz`
- `free_cursor_dataset.tar.gz.sha256`

Fallback supported by notebook:

- `/content/drive/MyDrive/free_cursor_dataset.tar.gz`
- `/content/drive/MyDrive/dataset.tar.gz`

## 2) Colab setup

Use T4 GPU runtime, then install:

```python
!pip install -q -U transformers peft trl bitsandbytes accelerate datasets sentencepiece optimum[onnxruntime] onnx onnxruntime
```

Mount Drive and extract:

```python
from google.colab import drive
import tarfile

drive.mount('/content/drive')
with tarfile.open('/content/drive/MyDrive/free_cursor_dataset.tar.gz', 'r:gz') as tar:
    tar.extractall('/content')
```

## 3) Train + evaluate

```python
!python /content/<your_repo>/scripts/ml/colab_train_qlora.py \
  --train /content/dataset/splits/train.jsonl \
  --val /content/dataset/splits/val.jsonl \
  --test /content/dataset/splits/test.jsonl \
  --lora-output /content/drive/MyDrive/free_cursor_lora_weights \
  --report-output /content/drive/MyDrive/free_cursor_eval_report.json
```

## 4) Merge and export ONNX

```python
!python /content/<your_repo>/scripts/ml/merge_and_export_onnx.py \
  --lora-dir /content/drive/MyDrive/free_cursor_lora_weights \
  --merged-dir /content/free_cursor_merged \
  --onnx-dir /content/drive/MyDrive/free_cursor_onnx_bundle
```

`free_cursor_onnx_bundle` should include:

- `model.onnx`
- `tokenizer.json`
- `tokenizer_config.json`
- `special_tokens_map.json`
- `generation_config.json`
