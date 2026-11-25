#!/usr/bin/env python3
"""Chemical structure image to SMILES recognition using molscribe + rdkit."""

import time
import sys
import os

def predict_smiles(image_path):
    """Predict SMILES from a chemical structure image using molscribe."""
    from molscribe import predict
    from rdkit import Chem
    
    start_time = time.time()
    
    # Use molscribe to predict SMILES from structure image
    # molscribe.predict returns a list of predictions with smiles and scores
    try:
        predictions = predict(image_path)
        if predictions:
            # Get the top prediction
            top = predictions[0]
            smiles = top.smiles
            confidence = getattr(top, 'score', None)
            if confidence is None:
                confidence = getattr(top, 'probability', None)
                if confidence is None:
                    confidence = None
        else:
            smiles = ""
            confidence = None
    except Exception as e:
        smiles = ""
        confidence = None
        print(f"Molscribe error for {image_path}: {e}", file=sys.stderr)
    
    # Use rdkit to compute molecular formula and canonical SMILES
    try:
        mol = Chem.MolFromSmiles(smiles)
        if mol is not None:
            canonical_smiles = Chem.MolToSmiles(mol)
            formula = Chem.GetFormulae(mol)
        else:
            canonical_smiles = smiles
            formula = "C?H?O?"
    except Exception as e:
        canonical_smiles = smiles
        formula = "C?H?O?"
        print(f"RDKit error for {image_path}: {e}", file=sys.stderr)
    
    elapsed = time.time() - start_time
    
    return {
        "smiles": smiles,
        "canonical_smiles": canonical_smiles,
        "formula": formula,
        "confidence": confidence,
        "elapsed": elapsed
    }

def main():
    inputs_dir = os.path.join(os.path.dirname(__file__), "inputs")
    out_dir = os.path.join(os.path.dirname(__file__), "out")
    os.makedirs(out_dir, exist_ok=True)
    
    results = {}
    
    # Process each image in the inputs directory
    image_files = sorted([f for f in os.listdir(inputs_dir) if f.endswith(('.png', '.jpg', '.jpeg'))])
    
    for img_name in image_files:
        img_path = os.path.join(inputs_dir, img_name)
        print(f"Processing {img_name}...")
        result = predict_smiles(img_path)
        results[img_name] = {
            "file": img_name,
            "smiles": result["smiles"],
            "formula": result["formula"],
            "canonical_smiles": result["canonical_smiles"],
            "confidence": result["confidence"],
            "elapsed": result["elapsed"]
        }
        print(f"  SMILES: {results[img_name]['smiles']}")
        print(f"  Formula: {results[img_name]['formula']}")
        print(f"  Confidence: {results[img_name]['confidence']}")
    
    # Write results to output JSON
    output_path = os.path.join(out_dir, "result.json")
    with open(output_path, "w", encoding="utf-8") as f:
        import json
        json.dump(results, f, ensure_ascii=False, indent=2)
    
    print(f"Results written to {output_path}")
    for name, res in results.items():
        print(f"  {name}: file={res['file']}, smiles={res['smiles']}, formula={res['formula']}, "
              f"canonical={res['canonical_smiles']}, confidence={res['confidence']}")

if __name__ == "__main__":
    main()