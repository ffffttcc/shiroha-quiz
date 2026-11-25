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
from pathlib import Path


def predict_smiles(image_path):
    """Predict SMILES and formula from a chemical structure image.

    Uses molscribe for SMILES prediction and rdkit for formula calculation.
    Returns dict with file, smiles, formula, canonical_smiles, confidence,
    and duration. If prediction fails, confidence is None and other fields
    contain reasonable fallbacks.
    """
    from rdkit import Chem

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
        # molscribe-based structure prediction
        try:
            from molscribe import predict as molscribe_predict
            predictions = molscribe_predict(image_path)
            if predictions and len(predictions) > 0:
                top = predictions[0]
                smiles = getattr(top, 'smiles', None) or getattr(top, 'smiles_string', None)
                confidence = getattr(top, 'score', None)
                if confidence is None:
                    confidence = getattr(top, 'probability', None)
            else:
                smiles = None
                confidence = None
        except Exception:
            smiles = None
            confidence = None

        # rdkit-based formula calculation and canonical SMILES
        if smiles is not None:
            try:
                mol = Chem.MolFromSmiles(smiles)
                if mol is not None:
                    formula = Chem.rdMolDescriptors.CalcMolFormula(mol)
                    canonical_smiles = Chem.MolToSmiles(mol)
                else:
                    # Invalid SMILES from molscribe; try to infer from image
                    formula = None
                    canonical_smiles = None
            except Exception:
                formula = None
                canonical_smiles = None
        else:
            formula = None
            canonical_smiles = None

        duration = time.time() - start_time

        result = {
            "file": file_name,
            "smiles": smiles,
            "formula": formula,
            "canonical_smiles": canonical_smiles,
            "confidence": confidence,
            "duration_seconds": duration
        }

    except Exception as e:
        print(f"Error processing {image_path}: {e}", file=sys.stderr)
        duration = time.time() - start_time
        result["duration_seconds"] = duration

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