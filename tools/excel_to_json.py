#!/usr/bin/env python3
"""
将「副本线路数据(1).xls」所有 Sheet 转为结构化 JSON
输出到 backend/src/main/resources/configs/ 目录
"""
import xlrd
import json
import os
import sys

EXCEL_PATH = os.path.join(os.path.dirname(__file__), "..", "副本线路数据(1).xls")
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "..", "backend", "src", "main", "resources", "configs")

def clean_key(s):
    """清理字段名"""
    if not s:
        return ""
    s = str(s).replace("\n", "").replace("\r", "").strip()
    # Remove trailing annotations in parentheses
    return s

def find_header_row(sheet):
    """找到包含最多中文/英文字段名的行作为表头"""
    best_row = 3  # default: row index 3
    best_count = 0
    for row_idx in range(min(6, sheet.nrows)):
        count = 0
        for col in range(sheet.ncols):
            v = sheet.cell_value(row_idx, col)
            if v and isinstance(v, str):
                s = str(v).strip()
                # Skip type codes and empty
                if s and len(s) >= 1 and s not in ('', '0x10', 'UDE2', 'UDE4', 'UDE1', 'UDE0',
                                                     'HEX0', 'HEX1', 'HEX2', 'HEX4',
                                                     'USTR20', 'USTR16', 'STR8', 'STR12', 'STR16'):
                    # Check if it looks like a Chinese or descriptive field name
                    has_chinese = any('\u4e00' <= c <= '\u9fff' or '\u3400' <= c <= '\u4dbf' for c in s)
                    has_alpha = any(c.isalpha() for c in s)
                    if has_chinese or (has_alpha and len(s) >= 2):
                        count += 1
        if count > best_count:
            best_count = count
            best_row = row_idx
    return best_row

def find_data_start(sheet, header_row):
    """找到第一个实际数据行（跳过表头）"""
    for row_idx in range(header_row + 1, sheet.nrows):
        has_data = False
        for col in range(min(sheet.ncols, 10)):
            v = sheet.cell_value(row_idx, col)
            if v != '' and v is not None:
                has_data = True
                break
        if has_data:
            return row_idx
    return sheet.nrows

def read_sheet(sheet):
    """读取一个 Sheet 并返回 records 列表"""
    header_row = find_header_row(sheet)
    
    # Build column names
    columns = []
    for col in range(sheet.ncols):
        v = sheet.cell_value(header_row, col)
        name = clean_key(v)
        if not name:
            name = f"col{col}"
        columns.append(name)
    
    data_start = find_data_start(sheet, header_row)
    
    records = []
    for row_idx in range(data_start, sheet.nrows):
        record = {}
        has_data = False
        for col in range(sheet.ncols):
            v = sheet.cell_value(row_idx, col)
            if v == '' or v is None:
                continue
            if isinstance(v, float) and (v == 65535.0 or v == 65535):
                continue  # skip sentinel values
            key = columns[col]
            # Convert float to int if it's whole number
            if isinstance(v, float):
                if v == int(v):
                    v = int(v)
                # Handle very large numbers (device IDs)
                if abs(v) > 2_000_000_000:
                    v = int(v)
            record[key] = v
            has_data = True
        if has_data:
            records.append(record)
    
    return records

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    wb = xlrd.open_workbook(EXCEL_PATH)
    
    all_data = {}
    
    sheet_name_map = {
        '长短链表': 'long-short-chain',
        '点表': 'points',
        'Seg表': 'segments',
        '计轴区段表': 'axle-counter-sections',
        '物理区段表': 'physical-sections',
        '逻辑区段表': 'logical-sections',
        '计轴器表': 'axle-counters',
        '道岔表': 'switches',
        '信号机表': 'signals',
        '屏蔽门表': 'platform-doors',
        '紧急按钮表': 'emergency-buttons',
        '车站表': 'stations',
        '站台表': 'platforms',
        '应答器表': 'balises',
        '坡度表': 'gradients',
        '静态限速表': 'static-speed-limits',
        '进路表': 'routes',
        '保护区段表': 'protection-sections',
        '点式接近区段表': 'point-approach-sections',
        'CBTC接近区段表': 'cbtc-approach-sections',
        '点式触发区段表': 'point-trigger-sections',
        'CBTC触发区段表': 'cbtc-trigger-sections',
        '区域属性表': 'zone-properties',
        '线路统一限速信息表': 'line-uniform-speed-limit',
        '线路统一坡度信息表': 'line-uniform-gradient',
        '防淹门表': 'flood-gates',
        '隧道表': 'tunnels',
        '车档表': 'bumpers',
        '设备编号映射表': 'device-id-mapping',
        '虚拟点表': 'virtual-points',
        'SPKS开关表': 'spks-switches',
        '车库门表': 'depot-doors',
        '碰撞区域表': 'collision-zones',
    }
    
    for sheet in wb.sheets():
        sname = sheet.name
        print(f"Processing: {sname} ({sheet.nrows} rows, {sheet.ncols} cols)")
        
        records = read_sheet(sheet)
        print(f"  -> {len(records)} records extracted")
        
        out_name = sheet_name_map.get(sname, sname.replace('表', '').lower())
        out_path = os.path.join(OUTPUT_DIR, f"{out_name}.json")
        
        with open(out_path, 'w', encoding='utf-8') as f:
            json.dump(records, f, ensure_ascii=False, indent=2)
        
        all_data[sname] = {"file": f"{out_name}.json", "count": len(records)}
    
    # Write summary
    summary_path = os.path.join(OUTPUT_DIR, "data-summary.json")
    with open(summary_path, 'w', encoding='utf-8') as f:
        json.dump(all_data, f, ensure_ascii=False, indent=2)
    
    print(f"\nDone! Output to {OUTPUT_DIR}")
    print(f"Summary written to {summary_path}")

if __name__ == '__main__':
    main()
