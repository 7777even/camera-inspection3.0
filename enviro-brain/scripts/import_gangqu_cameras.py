"""
将「港区小脑摄像头清单」CSV 中的摄像头幂等导入 enviro-brain 的 camera_config 表（PostgreSQL），
标记为 scenario='gangqu'。

CSV 格式（UTF-8 BOM，含表头）：
    摄像头编码    -> camera_code  (列 0，唯一键，海康 index code)
    监控点名称    -> camera_name  (列 1，为空时回退为 camera_code)

用法：
    python import_gangqu_cameras.py [--csv 路径]

幂等：使用 ON CONFLICT (camera_code) DO UPDATE；重复编码会更新名称/scenario 等，不会报错。
注意：本脚本仅写入 camera_code / camera_name / enabled / scenario / artemis_device_id / ledger_enabled，
      刻意不写入 created_at / updated_at / enterprise / rtsp_url / location 等列（这些列在现有
      import_cameras_from_excel.py 中也未写入，运行时由 DB 默认值/触发器填充，避免列不存在导致失败）。

依赖：psycopg2（运行时惰性导入，故 --help 不需要 psycopg2 即可运行）。
      若运行时缺失，会提示 pip install psycopg2-binary。
"""
import argparse
import csv
import os
import sys

DEFAULT_CSV = r"C:\Users\7even\Downloads\港区小脑摄像头清单.csv"

# DSN 默认值与兄弟脚本 import_cameras_from_excel.py 保持一致，支持环境变量覆盖。
PG_HOST = os.environ.get("PG_HOST", "172.168.97.180")
PG_PORT = os.environ.get("PG_PORT", "31028")
PG_DB = os.environ.get("PG_DB", "smartpark_scenes_zhh")
PG_USER = os.environ.get("PG_USER", "postgres")
PG_PASSWORD = os.environ.get("PG_PASSWORD", "postgresql")
DSN = (
    f"host={PG_HOST} port={PG_PORT} dbname={PG_DB} "
    f"user={PG_USER} password={PG_PASSWORD} sslmode=disable"
)

# 保守幂等 INSERT：仅写入已验证存在的列 + 新增的 scenario 维度。
# ledger_enabled=0 表示港区摄像头默认不推送到 enviro 台账（用户可后续手动翻转）。
# artemis_device_id=NULL：港区以 camera_code 作为海康 index code，device-id 路径为死代码。
SQL = """
INSERT INTO camera_config (camera_code, camera_name, enabled, scenario, artemis_device_id, ledger_enabled)
VALUES (%s, %s, 1, 'gangqu', NULL, 0)
ON CONFLICT (camera_code) DO UPDATE SET
    camera_name      = EXCLUDED.camera_name,
    scenario         = 'gangqu',
    enabled          = 1,
    artemis_device_id = NULL,
    ledger_enabled   = 0
"""


def main() -> None:
    parser = argparse.ArgumentParser(
        description="将港区小脑摄像头清单 CSV 幂等导入 camera_config(scenario='gangqu')。"
    )
    parser.add_argument(
        "--csv",
        default=DEFAULT_CSV,
        help="CSV 文件路径（默认：%(default)s）",
    )
    args = parser.parse_args()

    try:
        import psycopg2
    except ImportError:
        print(
            "错误：未安装 psycopg2。请先运行： pip install psycopg2-binary",
            file=sys.stderr,
        )
        sys.exit(1)

    # 读取 CSV（UTF-8 BOM + 末尾空行需处理）
    rows = []
    with open(args.csv, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        for r in reader:
            # 跳过完全空行
            if not r or all((c.strip() == "" for c in r)):
                continue
            code = r[0].strip() if len(r) > 0 else ""
            if not code:
                continue
            name = (r[1].strip() if len(r) > 1 and r[1] else code)
            rows.append((code, name))

    print(f"[1] 读取 CSV: {args.csv}")
    print(f"    有效数据行数={len(rows)}")

    conn = psycopg2.connect(DSN)
    cur = conn.cursor()

    for code, name in rows:
        cur.execute(SQL, (code, name))
    conn.commit()

    cur.execute("SELECT count(*) FROM camera_config WHERE scenario='gangqu'")
    m = cur.fetchone()[0]
    print(f"处理 {len(rows)} 行；camera_config 中 scenario='gangqu' 共 {m} 条")

    cur.close()
    conn.close()
    print("完成。")


if __name__ == "__main__":
    main()
