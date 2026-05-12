# QNN Model Prep

This folder contains a Windows-side workflow to prepare an ONNX model for Snapdragon QNN validation.

It does three things:

1. inspects the model inputs so you can see which shapes are still dynamic,
2. fixes dynamic dimensions to concrete values required by QNN HTP,
3. runs QNN preprocessing and QDQ quantization.

## Why this exists

The app-side QNN integration is already working. The current blocker is model compatibility:

- ONNX Runtime loads the QNN execution provider correctly.
- The imported public ONNX model still leaves some nodes on CPU.
- CPU fallback is intentionally disabled in the app, so initialization fails instead of silently mixing CPU and NPU.

This workflow is the next step to produce a more QNN-friendly model.

## Which model to download

For this repo, the best next download is `onnx/model_fp16.onnx`, not another pre-quantized variant.

- `model_fp16.onnx` is the cleanest starting point for your own QNN preprocessing and QDQ quantization.
- `model_uint8.onnx`, `model_int8.onnx`, and `model_quantized.onnx` are already quantized in a generic way, which is less useful when you need a QNN-friendly graph.
- This repo does not expose a cleaner `decoder_model_merged.onnx` or `decoder_model.onnx` artifact, so the practical path is either `model_fp16.onnx` or the `model_uint8.onnx` file you already downloaded.

If you already have `model_uint8.onnx`, you can still continue with it using the decoder-with-past mode below.

## Prerequisites

- Windows x64
- Python installed
- The local ONNX Runtime source checkout already present in this repo under `third_party/onnxruntime`

Install Python dependencies:

```powershell
Set-Location "c:\Users\HFX\Desktop\Jillian Projects\jg_scraper_android\jg_scraper_android\qnn_model_prep"
python -m pip install -r requirements.txt
```

## Step 1: Inspect model inputs

Run this first so you can see the exact input names and shapes:

```powershell
Set-Location "c:\Users\HFX\Desktop\Jillian Projects\jg_scraper_android\jg_scraper_android\qnn_model_prep"
python .\prepare_qnn_model.py --input-model "C:\path\to\model_uint8.onnx" --output-dir "C:\path\to\prepared" --inspect-only
```

If the model has symbolic or dynamic dimensions, the output will show which inputs still need to be fixed.

## Step 2: Fix shapes and quantize

Example command shape for a text model:

```powershell
Set-Location "c:\Users\HFX\Desktop\Jillian Projects\jg_scraper_android\jg_scraper_android\qnn_model_prep"
python .\prepare_qnn_model.py \
  --input-model "C:\path\to\model_uint8.onnx" \
  --output-dir "C:\path\to\prepared" \
  --input-shape input_ids=1,256 \
  --input-shape attention_mask=1,256 \
  --activation-type uint16 \
  --weight-type uint8 \
  --keep-intermediate-models
```

If the model uses symbolic names instead of per-input replacement, you can fix those directly:

```powershell
python .\prepare_qnn_model.py \
  --input-model "C:\path\to\model_uint8.onnx" \
  --output-dir "C:\path\to\prepared" \
  --dim-param batch_size=1 \
  --dim-param seq_len=256
```

For the current Qwen decoder-with-past export, use the automatic mode instead of manually listing every KV-cache tensor:

```powershell
python .\prepare_qnn_model.py \
  --input-model "C:\path\to\model_uint8.onnx" \
  --output-dir "C:\path\to\prepared" \
  --decoder-with-past \
  --batch-size 1 \
  --sequence-length 1 \
  --past-sequence-length 1 \
  --activation-type uint16 \
  --weight-type uint8 \
  --keep-intermediate-models
```

That mode automatically fixes:

- `input_ids` and `position_ids` to `[batch_size, sequence_length]`
- `attention_mask` to `[batch_size, past_sequence_length + sequence_length]`
- all `past_key_values.*` tensors to `[batch_size, 2, past_sequence_length, 64]`

## Notes

- The script uses synthetic calibration data. That is acceptable for an initial compatibility pass, but not ideal for quality.
- In decoder-with-past mode, KV-cache tensors are calibration-filled with zeros instead of random values.
- For better accuracy later, replace the synthetic reader with representative calibration samples.
- The default quantization choice is `uint16` activations and `uint8` weights because that is a better first try for QNN HTP than pure `uint8` activations.
- If the script says the model still has dynamic inputs, run `--inspect-only` again and add more `--input-shape` or `--dim-param` overrides.

## Output

The final file is written as:

- `<output-dir>/<original-name>.qnn.qdq.onnx`

That is the file to import back into the Android app for another NPU-only validation pass.