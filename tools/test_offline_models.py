#!/usr/bin/env python3
"""Real ONNX encoder/decoder generation with SentencePiece; no network calls."""
import gc
import json
from pathlib import Path
import resource
import time

import numpy as np
import onnxruntime as ort
import sentencepiece as spm

ROOT = Path(__file__).resolve().parents[1]


def translate(pair, text):
    path = ROOT / "app/src/main/assets/models" / pair
    vocab = json.loads((path / "vocab.json").read_text())
    reverse = {value: key for key, value in vocab.items()}
    config = json.loads((path / "config.json").read_text())
    source = spm.SentencePieceProcessor(model_file=str(path / "source.spm"))
    target = spm.SentencePieceProcessor(model_file=str(path / "target.spm"))
    pieces = source.encode(text, out_type=str)
    ids = ([vocab[">>cmn_Hans<<"]] if pair == "en-zh" else []) + [vocab.get(piece, vocab["<unk>"]) for piece in pieces] + [config["eos_token_id"]]
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    encoder = ort.InferenceSession(str(path / "onnx/encoder_model_quantized.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    decoder = ort.InferenceSession(str(path / "onnx/decoder_model_quantized.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    assert [i.name for i in encoder.get_inputs()] == ["input_ids", "attention_mask"]
    ids = np.asarray([ids], dtype=np.int64)
    mask = np.ones_like(ids)
    encoded = encoder.run(["last_hidden_state"], {"input_ids": ids, "attention_mask": mask})[0]
    generated = [config["decoder_start_token_id"]]
    for _ in range(128):
        logits = decoder.run(["logits"], {"input_ids": np.asarray([generated], dtype=np.int64),
                                          "encoder_attention_mask": mask, "encoder_hidden_states": encoded})[0][0, -1]
        logits[config["pad_token_id"]] = -np.inf
        token = int(np.argmax(logits))
        if token == config["eos_token_id"]:
            result = target.decode([reverse[t] for t in generated[1:]])
            assert result.strip() and result != text and vocab["<unk>"] not in generated
            del encoder, decoder, encoded
            gc.collect()
            return result
        generated.append(token)
    raise RuntimeError("Generation did not reach EOS; refusing truncated output")


if __name__ == "__main__":
    inputs = {"en": "Welcome to the village. Find the key and open the door.",
              "ru": "Добро пожаловать в деревню. Найдите ключ и откройте дверь.",
              "de": "Willkommen im Dorf. Finde den Schlüssel und öffne die Tür.",
              "fr": "Bienvenue au village. Trouvez la clé et ouvrez la porte.",
              "es": "Bienvenido al pueblo. Encuentra la llave y abre la puerta."}
    results = []
    for lang, text in inputs.items():
        started = time.monotonic()
        intermediate = translate(lang + "-en", text) if lang != "en" else text
        output = translate("en-zh", intermediate)
        assert any('\u4e00' <= c <= '\u9fff' for c in output)
        result = {"language": lang, "input": text, "english_pivot": intermediate if lang != "en" else None,
                  "output": output, "seconds": round(time.monotonic() - started, 3),
                  "process_peak_rss_kib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                  "eos_reached": True, "actual_inference": True}
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    (ROOT / "docs/model-test-results.json").write_text(json.dumps({"runtime": "ONNX Runtime CPU 1.22.0, SentencePiece 0.2.0",
        "platform": "Linux x86_64 host, NOT Android", "generation": "greedy, maximum 128 tokens, no past cache",
        "network_used_during_inference": False, "results": results}, ensure_ascii=False, indent=2) + "\n")
