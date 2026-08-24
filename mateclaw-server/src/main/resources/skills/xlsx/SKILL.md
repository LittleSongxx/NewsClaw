---
name: xlsx
description: "Use this skill any time a spreadsheet file is the primary input or output. This means any task where the user wants to: open, read, edit, or fix an existing .xlsx, .xlsm, .csv, or .tsv file; create a new spreadsheet from scratch or from other data sources; or convert between tabular file formats. Also trigger for cleaning or restructuring messy tabular data. The deliverable must be a spreadsheet file."
dependencies:
  commands:
    - python3
  tools:
    - skillScriptTool
    - skillFileTool
platforms:
  - macos
  - linux
  - windows
---

> **Important:** All `scripts/` paths are relative to this skill directory.
> Use `run_skill_script` tool to execute scripts, or run with: `cd {this_skill_dir} && python scripts/...`

# Requirements for Outputs

## All Excel files

### Professional Font
- Use a consistent, professional font (e.g., Arial, Times New Roman) unless otherwise instructed

### Zero Formula Errors
- Every Excel model MUST be delivered with ZERO formula errors (#REF!, #DIV/0!, #VALUE!, #N/A, #NAME?)

### Preserve Existing Templates
- Study and EXACTLY match existing format, style, and conventions when modifying files
- Existing template conventions ALWAYS override these guidelines

## Financial Models

### Color Coding Standards

- **Blue text (0,0,255)**: Hardcoded inputs
- **Black text (0,0,0)**: ALL formulas and calculations
- **Green text (0,128,0)**: Links from other worksheets
- **Red text (255,0,0)**: External links to other files
- **Yellow background (255,255,0)**: Key assumptions needing attention

### Number Formatting Standards

- **Years**: Format as text strings ("2024" not "2,024")
- **Currency**: Use $#,##0 format; specify units in headers ("Revenue ($mm)")
- **Zeros**: Format as "-" including percentages
- **Percentages**: Default to 0.0% format
- **Multiples**: Format as 0.0x
- **Negative numbers**: Use parentheses (123) not minus -123

### Formula Construction Rules

- Place ALL assumptions in separate assumption cells
- Use cell references instead of hardcoded values
- Example: Use `=B5*(1+$B$6)` instead of `=B5*1.05`

# XLSX creation, editing, and analysis

## Prerequisites

- **openpyxl**: Excel file creation and editing
- **pandas**: data analysis and bulk operations
- **LibreOffice** (`soffice`): formula recalculation via `scripts/recalc.py`

## CRITICAL: Use Formulas, Not Hardcoded Values

**Always use Excel formulas instead of calculating values in Python and hardcoding them.**

### WRONG - Hardcoding
```python
total = df['Sales'].sum()
sheet['B10'] = total  # Bad: hardcodes 5000
```

### CORRECT - Using Formulas
```python
sheet['B10'] = '=SUM(B2:B9)'
```

## Common Workflow

1. **Choose tool**: pandas for data, openpyxl for formulas/formatting
2. **Create/Load**: Create new workbook or load existing file
3. **Modify**: Add/edit data, formulas, and formatting
4. **Save**: Write to file
5. **Recalculate formulas (MANDATORY IF USING FORMULAS)**:
   ```bash
   python scripts/recalc.py output.xlsx
   ```
6. **Verify and fix any errors**:
   - If `status` is `errors_found`, check `error_summary` for specific errors
   - Fix the identified errors and recalculate again

## Reading and Analyzing Data

### Data analysis with pandas
```python
import pandas as pd

df = pd.read_excel('file.xlsx')                          # Default: first sheet
all_sheets = pd.read_excel('file.xlsx', sheet_name=None) # All sheets as dict

df.head()      # Preview data
df.info()      # Column info
df.describe()  # Statistics

df.to_excel('output.xlsx', index=False)
```

## Excel File Workflows

### Creating new Excel files
```python
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment

wb = Workbook()
sheet = wb.active

sheet['A1'] = 'Hello'
sheet['B1'] = 'World'
sheet.append(['Row', 'of', 'data'])

sheet['B2'] = '=SUM(A1:A10)'

sheet['A1'].font = Font(bold=True, color='FF0000')
sheet['A1'].fill = PatternFill('solid', start_color='FFFF00')
sheet['A1'].alignment = Alignment(horizontal='center')

sheet.column_dimensions['A'].width = 20

wb.save('output.xlsx')
```

### Editing existing Excel files
```python
from openpyxl import load_workbook

wb = load_workbook('existing.xlsx')
sheet = wb.active

sheet['A1'] = 'New Value'
sheet.insert_rows(2)
sheet.delete_cols(3)

new_sheet = wb.create_sheet('NewSheet')
new_sheet['A1'] = 'Data'

wb.save('modified.xlsx')
```

## Unpack/Pack Workflow (Advanced XML editing)

For advanced Excel manipulation via raw XML:

```bash
# Unpack
python scripts/office/unpack.py spreadsheet.xlsx unpacked/

# Edit XML in unpacked/xl/worksheets/, unpacked/xl/sharedStrings.xml, etc.

# Pack
python scripts/office/pack.py unpacked/ output.xlsx
```

## Recalculating Formulas

```bash
python scripts/recalc.py <excel_file> [timeout_seconds]
```

The script:
- Automatically sets up LibreOffice macro on first run
- Recalculates all formulas in all sheets
- Scans ALL cells for Excel errors
- Returns JSON with detailed error locations and counts
- Works on Linux, macOS, and Windows

### Interpreting recalc.py Output
```json
{
  "status": "success",
  "total_errors": 0,
  "total_formulas": 42,
  "error_summary": {}
}
```

## Formula Verification Checklist

### Essential Verification
- Test 2-3 sample references before building full model
- Confirm Excel column mapping (column 64 = BL, not BK)
- Remember Excel rows are 1-indexed (DataFrame row 5 = Excel row 6)

### Common Pitfalls
- NaN handling: Check for null values with `pd.notna()`
- Division by zero: Check denominators before `/` in formulas
- Wrong references: Verify all cell references point to intended cells
- Cross-sheet references: Use correct format (`Sheet1!A1`)

## Best Practices

### Library Selection
- **pandas**: Best for data analysis, bulk operations, and simple data export
- **openpyxl**: Best for complex formatting, formulas, and Excel-specific features

### Working with openpyxl
- Cell indices are 1-based
- Use `data_only=True` to read calculated values
- **Warning**: `data_only=True` + save = formulas permanently lost
- Formulas are preserved but not evaluated - use `scripts/recalc.py` to update values
