# Pilot Evaluation Fixtures

This directory contains fixtures for pilot evaluation of the NGJ compiler's AI agent integration.

## Test Scenarios

### 1. Simple Fill-In (`simple_fill.ngj`)
A basic function with one typed hole. The AI should fill in a simple arithmetic expression.

### 2. Multi-Hole (`multi_hole.ngj`)  
Multiple typed holes in related functions. Tests whether AI maintains consistency across holes.

### 3. Match Completion (`match_complete.ngj`)
A partial match expression. Tests AI's understanding of exhaustive matching.

### 4. Data Construction (`data_construct.ngj`)
Holes in data type construction. Tests understanding of field types and order.

## Usage

```bash
# Check a file with holes
ngjc check --format=json pricing_hole.ngj

# Get context for a specific hole
ngjc context --format=json --hole=subtotalCalc pricing_hole.ngj

# After AI fills holes, verify the result
ngjc check --format=json filled_result.ngj
```

## Expected Behavior

1. `check` returns exit code 2 (incomplete) for files with holes
2. `context` provides type information for the hole
3. After filling, `check` should return 0 (valid) or 1 (invalid)
