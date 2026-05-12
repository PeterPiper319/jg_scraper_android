from __future__ import annotations

import argparse
import importlib.util
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.quantization import CalibrationDataReader, CalibrationMethod, QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ORT_SOURCE = REPO_ROOT / "third_party" / "onnxruntime"


@dataclass
class PreparedPaths:
    fixed_model: Path
    preprocessed_model: Path
    optimized_model: Path
    quantized_model: Path


def parse_dim_fix(value: str) -> tuple[str, int]:
    name, raw = value.split("=", 1)
    return name.strip(), int(raw.strip())


def parse_input_shape(value: str) -> tuple[str, list[int]]:
    name, raw_shape = value.split("=", 1)
    shape = [int(part.strip()) for part in raw_shape.split(",") if part.strip()]
    return name.strip(), shape


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare an ONNX model for QNN validation by fixing shapes, preprocessing, and quantizing."
    )
    parser.add_argument("--input-model", type=Path, required=True, help="Path to the source ONNX model.")
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory for generated models.")
    parser.add_argument(
        "--ort-source",
        type=Path,
        default=DEFAULT_ORT_SOURCE,
        help="Path to the local ONNX Runtime source checkout used for shape-fix helpers.",
    )
    parser.add_argument(
        "--inspect-only",
        action="store_true",
        help="Print model inputs and stop. Use this first if you need shape override names.",
    )
    parser.add_argument(
        "--dim-param",
        action="append",
        default=[],
        type=parse_dim_fix,
        help="Replace a symbolic dimension across the graph. Example: --dim-param batch_size=1",
    )
    parser.add_argument(
        "--input-shape",
        action="append",
        default=[],
        type=parse_input_shape,
        help="Replace one model input shape. Example: --input-shape input_ids=1,256",
    )
    parser.add_argument(
        "--decoder-with-past",
        action="store_true",
        help="Auto-fix a decoder-with-past export using batch/sequence/past lengths.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=1,
        help="Batch size used when --decoder-with-past is enabled.",
    )
    parser.add_argument(
        "--sequence-length",
        type=int,
        default=1,
        help="Sequence length used when --decoder-with-past is enabled.",
    )
    parser.add_argument(
        "--past-sequence-length",
        type=int,
        default=1,
        help="KV-cache length used when --decoder-with-past is enabled. Must be at least 1 for this export.",
    )
    parser.add_argument(
        "--activation-type",
        choices=["uint8", "uint16"],
        default="uint16",
        help="Activation quantization type. uint16 is a better first try for QNN HTP compatibility.",
    )
    parser.add_argument(
        "--weight-type",
        choices=["uint8", "int8"],
        default="uint8",
        help="Weight quantization type.",
    )
    parser.add_argument(
        "--calibration-samples",
        type=int,
        default=8,
        help="Number of synthetic calibration samples to generate.",
    )
    parser.add_argument(
        "--token-upper-bound",
        type=int,
        default=32000,
        help="Upper bound for synthetic token/id generation for integer inputs.",
    )
    parser.add_argument(
        "--fuse-layernorm",
        action="store_true",
        help="Enable optional layernorm fusion during QNN preprocessing.",
    )
    parser.add_argument(
        "--keep-intermediate-models",
        action="store_true",
        help="Keep fixed/preprocessed models after quantization.",
    )
    return parser.parse_args()


def load_local_shape_helpers(ort_source: Path):
    helper_path = ort_source / "tools" / "python" / "util" / "onnx_model_utils.py"
    if not helper_path.exists():
        raise FileNotFoundError(f"Unable to find ORT shape helper at {helper_path}")

    spec = importlib.util.spec_from_file_location("ort_onnx_model_utils", helper_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load helper module from {helper_path}")

    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def tensor_shape_to_text(value_info: onnx.ValueInfoProto) -> str:
    if not value_info.type.HasField("tensor_type"):
        return "<non-tensor>"

    dims: list[str] = []
    for dim in value_info.type.tensor_type.shape.dim:
        if dim.HasField("dim_value"):
            dims.append(str(dim.dim_value))
        elif dim.HasField("dim_param"):
            dims.append(dim.dim_param)
        else:
            dims.append("?")
    return "[" + ", ".join(dims) + "]"


def print_model_inputs(model_path: Path) -> None:
    model = onnx.load_model(model_path)
    print(f"Model inputs for {model_path}:")
    for value_info in model.graph.input:
        elem_type = value_info.type.tensor_type.elem_type if value_info.type.HasField("tensor_type") else None
        elem_type_name = TensorProto.DataType.Name(elem_type) if elem_type is not None else "UNKNOWN"
        print(f"  {value_info.name}: type={elem_type_name}, shape={tensor_shape_to_text(value_info)}")


def apply_decoder_with_past_shape_fixes(model: onnx.ModelProto, helpers, args: argparse.Namespace) -> None:
    if args.batch_size < 1 or args.sequence_length < 1 or args.past_sequence_length < 1:
        raise ValueError(
            "Decoder-with-past mode requires batch-size >= 1, sequence-length >= 1, and past-sequence-length >= 1."
        )

    has_kv_cache_inputs = any(value_info.name.startswith("past_key_values.") for value_info in model.graph.input)
    if not has_kv_cache_inputs:
        raise ValueError("--decoder-with-past was set, but the model does not expose past_key_values.* inputs.")

    batch_size = args.batch_size
    sequence_length = args.sequence_length
    past_sequence_length = args.past_sequence_length
    attention_mask_length = past_sequence_length + sequence_length

    for value_info in model.graph.input:
        if not value_info.type.HasField("tensor_type"):
            continue

        input_name = value_info.name
        if input_name in {"input_ids", "position_ids"}:
            helpers.make_input_shape_fixed(model.graph, input_name, [batch_size, sequence_length])
        elif input_name == "attention_mask":
            helpers.make_input_shape_fixed(model.graph, input_name, [batch_size, attention_mask_length])
        elif input_name.startswith("past_key_values."):
            helpers.make_input_shape_fixed(model.graph, input_name, [batch_size, 2, past_sequence_length, 64])


def apply_shape_fixes(source_model: Path, output_model: Path, ort_source: Path, args: argparse.Namespace) -> None:
    model = onnx.load_model(source_model)
    helpers = load_local_shape_helpers(ort_source)

    if args.decoder_with_past:
        apply_decoder_with_past_shape_fixes(model, helpers, args)

    for dim_param, dim_value in args.dim_param:
        helpers.make_dim_param_fixed(model.graph, dim_param, dim_value)

    for input_name, fixed_shape in args.input_shape:
        helpers.make_input_shape_fixed(model.graph, input_name, fixed_shape)

    helpers.fix_output_shapes(model)
    onnx.save_model(model, output_model)


def validate_fixed_shapes(model_path: Path) -> None:
    model = onnx.load_model(model_path)
    unresolved: list[str] = []
    for value_info in model.graph.input:
        if not value_info.type.HasField("tensor_type"):
            continue
        for dim in value_info.type.tensor_type.shape.dim:
            if dim.HasField("dim_param") or not dim.HasField("dim_value"):
                unresolved.append(f"{value_info.name}:{tensor_shape_to_text(value_info)}")
                break

    if unresolved:
        joined = "\n  ".join(unresolved)
        raise ValueError(
            "Model still has dynamic input shapes after applying fixes. Add --dim-param or --input-shape for:\n  "
            + joined
        )


def ensure_model_is_not_prequantized(model_path: Path) -> None:
    model = onnx.load_model(model_path, load_external_data=False)
    qdq_ops = {"QuantizeLinear", "DequantizeLinear", "DynamicQuantizeLinear", "MatMulNBits"}

    has_qdq_nodes = any(node.op_type in qdq_ops for node in model.graph.node)
    has_quantized_initializers = any(
        initializer.name.endswith(("_scale", "_zero_point", "_quantized")) for initializer in model.graph.initializer
    )

    if has_qdq_nodes or has_quantized_initializers:
        raise ValueError(
            "Input model already appears to be quantized. Use a floating-point source model such as model_fp16.onnx "
            "or model.onnx before running QNN preprocessing and QDQ quantization."
        )


def quant_type_from_name(name: str) -> QuantType:
    mapping = {
        "uint8": QuantType.QUInt8,
        "uint16": QuantType.QUInt16,
        "int8": QuantType.QInt8,
    }
    return mapping[name]


def model_uses_fp16(model_path: Path) -> bool:
    model = onnx.load_model(model_path, load_external_data=False)

    for value_info in list(model.graph.input) + list(model.graph.output) + list(model.graph.value_info):
        if value_info.type.HasField("tensor_type") and value_info.type.tensor_type.elem_type == TensorProto.FLOAT16:
            return True

    return any(initializer.data_type == TensorProto.FLOAT16 for initializer in model.graph.initializer)


def normalize_qdq_scale_types(model_path: Path, target_dtype: int | None = None) -> None:
    model = onnx.load_model(model_path, load_external_data=False)
    initializer_map = {initializer.name: initializer for initializer in model.graph.initializer}

    elem_type_by_name: dict[str, int] = {}
    for value_info in list(model.graph.input) + list(model.graph.output) + list(model.graph.value_info):
        if value_info.type.HasField("tensor_type"):
            elem_type_by_name[value_info.name] = value_info.type.tensor_type.elem_type

    for initializer in model.graph.initializer:
        elem_type_by_name[initializer.name] = initializer.data_type

    scale_target_type: dict[str, int] = {}
    for node in model.graph.node:
        if node.op_type not in {"QuantizeLinear", "DequantizeLinear"} or len(node.input) < 2:
            continue

        scale_name = node.input[1]
        input_name = node.input[0]
        input_type = elem_type_by_name.get(input_name)
        if input_type in {TensorProto.FLOAT, TensorProto.FLOAT16}:
            scale_target_type[scale_name] = input_type
        elif target_dtype in {TensorProto.FLOAT, TensorProto.FLOAT16}:
            scale_target_type[scale_name] = target_dtype

    updated = False
    for scale_name, desired_type in scale_target_type.items():
        initializer = initializer_map.get(scale_name)
        if initializer is None or initializer.data_type == desired_type:
            continue

        if desired_type == TensorProto.FLOAT16:
            array = numpy_helper.to_array(initializer).astype(np.float16)
        elif desired_type == TensorProto.FLOAT:
            array = numpy_helper.to_array(initializer).astype(np.float32)
        else:
            continue

        new_initializer = numpy_helper.from_array(array, scale_name)
        initializer.CopyFrom(new_initializer)
        elem_type_by_name[scale_name] = desired_type
        updated = True

    if not updated:
        return

    for value_info in list(model.graph.input) + list(model.graph.output) + list(model.graph.value_info):
        desired_type = scale_target_type.get(value_info.name)
        if desired_type is not None and value_info.type.HasField("tensor_type"):
            value_info.type.tensor_type.elem_type = desired_type

    onnx.save_model(model, model_path)


def validate_loadable_model(model_path: Path) -> None:
    session_options = ort.SessionOptions()
    session_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    _ = ort.InferenceSession(str(model_path), sess_options=session_options, providers=["CPUExecutionProvider"])


class SyntheticCalibrationReader(CalibrationDataReader):
    def __init__(self, model_path: Path, sample_count: int, token_upper_bound: int):
        session_options = ort.SessionOptions()
        session_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        self._session = ort.InferenceSession(
            str(model_path),
            sess_options=session_options,
            providers=["CPUExecutionProvider"],
        )
        self._samples = self._build_samples(sample_count, token_upper_bound)
        self._iterator: Iterator[dict[str, np.ndarray]] | None = None

    def _build_samples(self, sample_count: int, token_upper_bound: int) -> list[dict[str, np.ndarray]]:
        samples: list[dict[str, np.ndarray]] = []
        for sample_index in range(sample_count):
          sample: dict[str, np.ndarray] = {}
          for model_input in self._session.get_inputs():
              shape = []
              for dim in model_input.shape:
                  if isinstance(dim, str) or dim is None:
                      raise ValueError(
                          f"Input {model_input.name} still has dynamic shape {model_input.shape}. Fix shapes first."
                      )
                  shape.append(int(dim))
              sample[model_input.name] = create_synthetic_input(
                  model_input.name,
                  model_input.type,
                  shape,
                  token_upper_bound,
                  sample_index,
              )
          samples.append(sample)
        return samples

    def get_next(self):
        if self._iterator is None:
            self._iterator = iter(self._samples)
        return next(self._iterator, None)

    def rewind(self):
        self._iterator = None


def create_synthetic_input(
    input_name: str,
    input_type: str,
    shape: list[int],
    token_upper_bound: int,
    sample_index: int,
) -> np.ndarray:
    lower_name = input_name.lower()
    token_like = any(keyword in lower_name for keyword in ["input_ids", "token", "ids", "indices"])
    mask_like = "mask" in lower_name
    cache_like = lower_name.startswith("past_key_values.") or lower_name.startswith("present.")
    position_like = lower_name == "position_ids"

    if position_like:
        values = np.arange(shape[-1], dtype=np.int64)
        tiled = np.tile(values, int(np.prod(shape[:-1])) if len(shape) > 1 else 1)
        return tiled.reshape(shape)

    if input_type == "tensor(int64)":
        if mask_like:
            return np.ones(shape, dtype=np.int64)
        if token_like:
            return np.random.default_rng(sample_index).integers(0, token_upper_bound, size=shape, dtype=np.int64)
        return np.zeros(shape, dtype=np.int64)

    if input_type == "tensor(int32)":
        if mask_like:
            return np.ones(shape, dtype=np.int32)
        if token_like:
            return np.random.default_rng(sample_index).integers(0, token_upper_bound, size=shape, dtype=np.int32)
        return np.zeros(shape, dtype=np.int32)

    if input_type == "tensor(bool)":
        return np.ones(shape, dtype=bool)

    if input_type in {"tensor(float)", "tensor(float32)"}:
        if cache_like:
            return np.zeros(shape, dtype=np.float32)
        return np.random.default_rng(sample_index).random(shape, dtype=np.float32)

    if input_type == "tensor(float16)":
        if cache_like:
            return np.zeros(shape, dtype=np.float16)
        return np.random.default_rng(sample_index).random(shape, dtype=np.float32).astype(np.float16)

    if input_type == "tensor(double)":
        return np.random.default_rng(sample_index).random(shape).astype(np.float64)

    if input_type == "tensor(int8)":
        return np.zeros(shape, dtype=np.int8)

    if input_type == "tensor(uint8)":
        return np.zeros(shape, dtype=np.uint8)

    raise ValueError(f"Unsupported calibration input type for {input_name}: {input_type}")


def prepare_paths(output_dir: Path, input_model: Path) -> PreparedPaths:
    stem = input_model.stem
    return PreparedPaths(
        fixed_model=output_dir / f"{stem}.fixed.onnx",
        preprocessed_model=output_dir / f"{stem}.preproc.onnx",
        optimized_model=output_dir / f"{stem}.opt.onnx",
        quantized_model=output_dir / f"{stem}.qnn.qdq.onnx",
    )


def maybe_cleanup(paths: PreparedPaths) -> None:
    for path in [paths.fixed_model, paths.preprocessed_model, paths.optimized_model]:
        if path.exists():
            path.unlink()


def optimize_preprocessed_model(source_model: Path, output_model: Path, ort_source: Path) -> None:
    helpers = load_local_shape_helpers(ort_source)
    helpers.optimize_model(
        source_model,
        output_model,
        level=ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
    )


def main() -> None:
    args = parse_args()
    input_model = args.input_model.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    if args.inspect_only:
        print_model_inputs(input_model)
        return

    paths = prepare_paths(output_dir, input_model)

    apply_shape_fixes(input_model, paths.fixed_model, args.ort_source.resolve(), args)
    validate_fixed_shapes(paths.fixed_model)

    modified = qnn_preprocess_model(
        paths.fixed_model,
        paths.preprocessed_model,
        fuse_layernorm=args.fuse_layernorm,
    )
    if not modified:
        shutil.copyfile(paths.fixed_model, paths.preprocessed_model)

    optimize_preprocessed_model(paths.preprocessed_model, paths.optimized_model, args.ort_source.resolve())

    ensure_model_is_not_prequantized(paths.optimized_model)

    reader = SyntheticCalibrationReader(
        model_path=paths.optimized_model,
        sample_count=args.calibration_samples,
        token_upper_bound=args.token_upper_bound,
    )

    quant_config = get_qnn_qdq_config(
        model_input=paths.optimized_model,
        calibration_data_reader=reader,
        calibrate_method=CalibrationMethod.MinMax,
        activation_type=quant_type_from_name(args.activation_type),
        weight_type=quant_type_from_name(args.weight_type),
        calibration_providers=["CPUExecutionProvider"],
    )
    quantize(paths.optimized_model, paths.quantized_model, quant_config=quant_config)
    normalize_qdq_scale_types(
        paths.quantized_model,
        TensorProto.FLOAT16 if model_uses_fp16(paths.optimized_model) else TensorProto.FLOAT,
    )
    validate_loadable_model(paths.quantized_model)

    print(f"Wrote fixed model: {paths.fixed_model}")
    print(f"Wrote preprocessed model: {paths.preprocessed_model}")
    print(f"Wrote optimized model: {paths.optimized_model}")
    print(f"Wrote quantized model: {paths.quantized_model}")
    print("Next step: import the .qnn.qdq.onnx model into the Android app and validate on-device.")

    if not args.keep_intermediate_models:
        maybe_cleanup(paths)
        print("Intermediate fixed/preprocessed models were removed. Re-run with --keep-intermediate-models if needed.")


if __name__ == "__main__":
    main()