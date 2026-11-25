#!/usr/bin/env python3
"""Chemical structure image to SMILES recognition using molscribe + rdkit.

Processes chemistry structure diagram images and predicts SMILES strings
with molecular formulas using molscribe and rdkit. Cloud-only execution:
no torch installed locally; molscribe and rdkit are installed at runtime.
"""

import time
import json
import os
import sys
import traceback
from pathlib import Path


def predict_smiles(image_path):
    """Predict SMILES and formula from a chemical structure image.

    Uses molscribe for SMILES prediction and rdkit for formula calculation.
    Returns dict with file, smiles, formula, canonical_smiles, confidence,
    and duration. If prediction fails, confidence is None and other fields
    contain reasonable fallbacks.
    """
    import torch

    from huggingface_hub import hf_hub_download
    from molscribe import MolScribe

    start_time = time.time()
    file_name = Path(image_path).name

    # Default result in case of failure
    result = {
        "file": file_name,
        "smiles": None,
        "formula": None,
        "canonical_smiles": None,
        "confidence": None,
        "duration_seconds": 0.0
    }

    try:
        # --- explicit model weight download ---
        print(f"[START] Downloading MolScribe checkpoint for {file_name}", flush=True)
        ckpt_path = hf_hub_download('yujieq/MolScribe', 'swin_base_char_aux_1m.pth')
        print(f"[CHECKPOINT] Path: {ckpt_path}", flush=True)

        device = torch.device('cpu')
        model = MolScribe(ckpt_path, device=device)

        # --- predict ---
        print(f"[PREDICT] Running prediction on {file_name}", flush=True)
        out = model.predict_image_file(image_path)   # 返回 dict，含 smiles / formula 等字段
        duration = time.time() - start_time

        smiles = out.get('smiles') if out else None
        formula = out.get('formula') if out else None
        canonical = out.get('canonical_smiles') if out else None
        confidence = out.get('confidence') if out else None

        # --- validate smiles ---
        if not smiles or smiles.strip() == '':
            print(f"[FAIL] No SMILES output for {file_name}", flush=True)
            sys.exit(2)

        result = {
            "file": file_name,
            "smiles": smiles,
            "formula": formula,
            "canonical_smiles": canonical,
            "confidence": confidence,
            "duration_seconds": duration
        }

    except Exception as e:
        print(f"Error processing {image_path}: {traceback.format_exc()}", flush=True)
        sys.exit(1)

    return result


def main():
    """Main entry point: process all images in chem-ocr/inputs/ and output results."""
    base_dir = Path(__file__).parent
    inputs_dir = base_dir / "inputs"
    out_dir = base_dir / "out"

    out_dir.mkdir(parents=True, exist_ok=True)

    # Find all image files in inputs directory
    image_files = []
    if inputs_dir.exists():
        image_files = sorted(
            list(inputs_dir.glob("*.png")) + list(inputs_dir.glob("*.jpg")) + list(inputs_dir.glob("*.jpeg"))
        )

    results = []

    for img_path in image_files:
        print(f"Processing: {img_path}")
        result = predict_smiles(str(img_path))
        results.append(result)
        print(f"  -> SMILES: {result['smiles']}, Formula: {result['formula']}, "
              f"Canonical: {result['canonical_smiles']}, Confidence: {result['confidence']}, "
              f"Duration: {result['duration_seconds']:.2f}s")

    # Write results to result.json
    output_path = out_dir / "result.json"
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\nResults written to: {output_path}")
    print(f"Total images processed: {len(results)}")

    return 0


if __name__ == "__main__":
    sys.exit(main())