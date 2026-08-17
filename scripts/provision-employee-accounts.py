#!/usr/bin/env python3
"""
神州HR 在职员工账号批量开通脚本
============================================================
用法：
  python3 scripts/provision-employee-accounts.py \
      --base-url http://127.0.0.1:8080 \
      --username szsc_admin_faa41d5bd802 \
      --password "$SHENZHOUHR_ADMIN_PASSWORD" \
      --output /tmp/szhr-credentials.csv

要求：
  - 调用账号需有 ACCOUNT:CREATE 和 ROLE:ASSIGN 权限
  - 每批最多 20 人（由服务端强制）
  - 一次性密码文件不进 Git，权限 0600
  - 可重放：已有账号的员工会被候选 API 跳过（status != AVAILABLE）

注意：
  - --recovery-key 不提供时由脚本自动生成（推荐）
  - 已有账号的员工不会重复创建（服务端幂等）
  - 需保管好输出 CSV，里面含明文临时密码
============================================================
"""

import argparse
import csv
import json
import os
import secrets
import sys
import time
import base64
from typing import Optional

try:
    import http.cookiejar
    import urllib.request
    import urllib.error
    import urllib.parse
except ImportError:
    print("ERROR: Python 3.x required", file=sys.stderr)
    sys.exit(1)


BASE_API = "/api/v1"
BATCH_SIZE = 20  # 服务端 MAX_BULK_ACCOUNT_COUNT = 20
PAGE_SIZE = 100  # 服务端 @Max(100)

COMPANY_IDS = [
    "41000000-0000-0000-0000-000000000001",
    "41000000-0000-0000-0000-000000000002",
    "41000000-0000-0000-0000-000000000003",
    "41000000-0000-0000-0000-000000000004",
]


def make_request(
    url: str,
    method: str = "GET",
    data: Optional[dict] = None,
    headers: Optional[dict] = None,
    cookie: Optional[str] = None,
) -> tuple[int, dict]:
    """Make an HTTP request and return (status_code, response_body)."""
    req_headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    if cookie:
        req_headers["Cookie"] = cookie

    body = json.dumps(data).encode("utf-8") if data else None
    req = urllib.request.Request(url, data=body, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            body = json.loads(exc.read().decode("utf-8"))
        except Exception:
            body = {"error": str(exc)}
        return exc.code, body


def login(base_url: str, username: str, password: str) -> tuple[str, str]:
    """Log in and return (session_cookie, csrf_token)."""
    url = f"{base_url}{BASE_API}/auth/login"
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    req = urllib.request.Request(
        url,
        data=json.dumps({"username": username, "password": password}).encode(),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    csrf_token = None
    try:
        with opener.open(req) as resp:
            if resp.status not in (200, 201):
                print(f"ERROR: Login failed ({resp.status})", file=sys.stderr)
                sys.exit(1)
            # X-CSRF-TOKEN is returned by AuthenticationController
            csrf_token = resp.headers.get("X-CSRF-TOKEN")
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read().decode("utf-8")
        except Exception:
            body = str(exc)
        print(f"ERROR: Login failed ({exc.code}): {body[:200]}", file=sys.stderr)
        sys.exit(1)

    cookies = "; ".join(f"{c.name}={c.value}" for c in jar)
    if not cookies:
        print("ERROR: No session cookie returned after login", file=sys.stderr)
        sys.exit(1)
    if not csrf_token:
        print("ERROR: No X-CSRF-TOKEN header returned after login", file=sys.stderr)
        sys.exit(1)
    print(f"  ✓ 登录成功（session cookie 已获取，CSRF token 已获取）")
    return cookies, csrf_token


def get_candidates(base_url: str, company_id: str, cookie: str) -> list[dict]:
    """Return all AVAILABLE employee candidates for a company."""
    candidates = []
    page = 0
    while True:
        url = (
            f"{base_url}{BASE_API}/access/account-provisioning/candidates"
            f"?companyId={urllib.parse.quote(company_id)}"
            f"&size={PAGE_SIZE}&page={page}"
        )
        status, resp = make_request(url, "GET", cookie=cookie)
        if status != 200:
            print(f"  WARN: candidates API failed for company {company_id} ({status}): {resp}",
                  file=sys.stderr)
            break
        items = resp.get("items") or resp.get("candidates") or resp.get("content") or []
        if not items:
            # Try top-level list
            if isinstance(resp, list):
                items = resp
        for item in items:
            if item.get("status") == "AVAILABLE":
                candidates.append(item)
        total = resp.get("total", len(items))
        page_count = (total + PAGE_SIZE - 1) // PAGE_SIZE
        if page + 1 >= page_count or not items:
            break
        page += 1
        time.sleep(0.1)
    return candidates


def generate_recovery_key() -> str:
    """Generate a 32-byte base64url recovery key (43 chars, no padding)."""
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")


def provision_batch(
    base_url: str,
    employee_ids: list[str],
    recovery_key: str,
    idempotency_key: str,
    cookie: str,
    csrf_token: str,
) -> list[dict]:
    """Provision accounts for a batch of ≤ 20 employees. Returns credentials list."""
    url = f"{base_url}{BASE_API}/access/account-provisioning/accounts"
    headers = {
        "Idempotency-Key": idempotency_key,
        "Provisioning-Recovery-Key": recovery_key,
        "X-CSRF-TOKEN": csrf_token,
    }
    status, resp = make_request(
        url, "POST", {"employeeIds": employee_ids}, headers=headers, cookie=cookie
    )
    if status not in (200, 201):
        print(f"  ERROR: provisioning failed ({status}): {resp}", file=sys.stderr)
        return []
    credentials = resp.get("credentials") or []
    return credentials


def chunked(lst: list, size: int):
    for i in range(0, len(lst), size):
        yield lst[i : i + size]


def main():
    parser = argparse.ArgumentParser(description="神州HR 员工账号批量开通")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="API 基础地址")
    parser.add_argument("--username", required=True, help="管理员账号")
    parser.add_argument("--password", required=True, help="管理员密码")
    parser.add_argument(
        "--output",
        default="/tmp/szhr-dev/employee-credentials.csv",
        help="临时密码清单输出路径（权限 0600）",
    )
    parser.add_argument(
        "--recovery-key",
        default=None,
        help="32字节 base64url 恢复密钥（不填则自动生成）",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="仅列出待开通员工，不实际创建账号",
    )
    args = parser.parse_args()

    # Ensure output directory exists
    out_dir = os.path.dirname(args.output)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    recovery_key = args.recovery_key or generate_recovery_key()
    if len(recovery_key) != 43:
        print(
            "ERROR: recovery-key 必须是 43 字符 base64url（32字节无填充）", file=sys.stderr
        )
        sys.exit(1)

    print("=" * 60)
    print("神州HR 员工账号批量开通")
    print("=" * 60)
    print(f"  Base URL : {args.base_url}")
    print(f"  Output   : {args.output}")
    print(f"  Dry-run  : {args.dry_run}")
    print()

    # Step 1: Login
    print("1. 登录…")
    cookie, csrf_token = login(args.base_url, args.username, args.password)

    # Step 2: Collect all candidates across companies
    print("2. 查询待开通员工候选列表…")
    all_candidates = []
    for company_id in COMPANY_IDS:
        candidates = get_candidates(args.base_url, company_id, cookie)
        print(f"  公司 {company_id[-8:]} → {len(candidates)} 人待开通")
        all_candidates.extend(candidates)

    # Deduplicate by employee_id
    seen = set()
    unique_candidates = []
    for c in all_candidates:
        eid = c.get("employeeId") or c.get("employee_id") or c.get("id")
        if eid and eid not in seen:
            seen.add(eid)
            unique_candidates.append({"employeeId": eid, **c})

    print(f"\n  共 {len(unique_candidates)} 名员工待开通账号")

    if not unique_candidates:
        print("\n✓ 无需开通（所有员工已有账号或无候选记录）")
        return

    if args.dry_run:
        print("\n[DRY RUN] 以下员工将被开通：")
        for c in unique_candidates[:10]:
            print(f"  {c.get('employeeNumber','?'):12s}  {c.get('displayName','?')}")
        if len(unique_candidates) > 10:
            print(f"  ... 及其余 {len(unique_candidates) - 10} 人")
        return

    # Step 3: Provision in batches of 20
    print(f"\n3. 分批开通（每批 {BATCH_SIZE} 人，共 {(len(unique_candidates)+BATCH_SIZE-1)//BATCH_SIZE} 批）…")
    all_credentials = []
    batch_num = 0
    for batch in chunked(unique_candidates, BATCH_SIZE):
        batch_num += 1
        employee_ids = [c["employeeId"] for c in batch]
        idempotency_key = f"bulk-provision-2026-{batch_num:04d}-{secrets.token_hex(8)}"
        print(f"  批次 {batch_num:02d}: {len(employee_ids)} 人…", end=" ", flush=True)
        creds = provision_batch(
            args.base_url, employee_ids, recovery_key, idempotency_key, cookie, csrf_token
        )
        all_credentials.extend(creds)
        print(f"✓ ({len(creds)} 条凭据返回)")
        time.sleep(0.2)  # 避免过于频繁的请求

    # Step 4: Write credentials CSV
    print(f"\n4. 写入凭据文件 {args.output} …")
    with open(args.output, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(
            ["工号", "姓名", "部门", "登录账号", "临时密码", "账号ID", "员工ID"]
        )
        for cred in all_credentials:
            writer.writerow([
                cred.get("employeeNumber", ""),
                cred.get("displayName", ""),
                cred.get("organizationName", ""),
                cred.get("username", ""),
                cred.get("temporaryPassword", ""),
                cred.get("accountId", ""),
                cred.get("employeeId", ""),
            ])
    os.chmod(args.output, 0o600)

    print(f"\n{'=' * 60}")
    print(f"✓ 完成！共开通 {len(all_credentials)} 个账号")
    print(f"  凭据文件：{args.output}（权限 0600）")
    print(f"  临时密码请在首次登录后立即修改")
    print(f"\n  ⚠️  恢复密钥（请妥善保管，可用于凭据恢复）：")
    print(f"  RECOVERY_KEY={recovery_key}")
    print("=" * 60)


if __name__ == "__main__":
    main()
