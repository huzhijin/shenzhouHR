#!/usr/bin/env python3
"""
部署注册工具 - 记录已验证的部署到历史记录

用法:
    # Runtime模式(必须提供receipt路径)
    python3 register-deployment.py \
        --release-root /path/to/release \
        --receipt /path/to/backup/INSTALL-RECEIPT.json \
        --title "十月HR修复" \
        --description "简要描述" \
        --details "- 修复1\n- 修复2" \
        [--history /var/shenzhouhr/deployment-history.json]

    # 历史导入模式
    python3 register-deployment.py \
        --release-root /archive/releases/v1.2.3 \
        --installed-jar /archive/v1.2.3/app.jar \
        --installed-web /archive/v1.2.3/web \
        --title "历史导入" \
        --installed-at "2026-10-10T09:00:00+08:00" \
        --verified-at "2026-10-10T10:00:00+08:00" \
        --no-runtime-verify
"""

import json
import os
import sys
import fcntl
import hashlib
import subprocess
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


DEFAULT_HISTORY_PATH = "/var/shenzhouhr/deployment-history.json"


def compute_sha256(file_path: Path) -> str:
    """计算文件SHA256摘要"""
    if not file_path.exists():
        raise ValueError(f"文件不存在：{file_path}")
    sha256 = hashlib.sha256()
    with open(file_path, 'rb') as f:
        while chunk := f.read(8192):
            sha256.update(chunk)
    return sha256.hexdigest()


def validate_sha256(value: str) -> bool:
    """验证SHA256格式"""
    return bool(re.match(r'^[0-9a-f]{64}$', value.lower()))


def parse_iso8601_with_tz(value: str) -> datetime:
    """解析ISO 8601时间戳，必须包含timezone"""
    try:
        dt = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if dt.tzinfo is None:
            raise ValueError("时间戳必须包含时区信息")
        return dt
    except (ValueError, AttributeError) as e:
        raise ValueError(f"无效的ISO 8601时间戳：{value}")


def load_manifest(release_root: Path) -> Dict[str, Any]:
    """加载BUILD-MANIFEST (支持.txt或.json格式)"""
    json_manifest = release_root / "BUILD-MANIFEST.json"
    txt_manifest = release_root / "BUILD-MANIFEST.txt"

    if json_manifest.exists():
        with open(json_manifest, 'r', encoding='utf-8') as f:
            data = json.load(f)
            if not isinstance(data, dict):
                raise ValueError("BUILD-MANIFEST.json格式错误")
            if 'releaseName' not in data:
                raise ValueError("BUILD-MANIFEST.json缺少releaseName字段")
            result = {
                'release_name': data['releaseName'],
                'backend_jar': data.get('backend_jar', 'backend/shenzhou-hr.jar'),
                'frontend_dir': data.get('frontend_dir', 'web')
            }
            if 'jarSha256' in data:
                if not validate_sha256(data['jarSha256']):
                    raise ValueError(f"BUILD-MANIFEST.json中jarSha256格式无效：{data['jarSha256']}")
                result['jarSha256'] = data['jarSha256'].lower()
            return result
    elif txt_manifest.exists():
        manifest = {}
        with open(txt_manifest, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if '=' in line and not line.startswith('#'):
                    key, value = line.split('=', 1)
                    manifest[key.strip()] = value.strip()
        if 'release_name' not in manifest:
            raise ValueError("BUILD-MANIFEST.txt缺少release_name字段")
        return manifest
    else:
        raise ValueError(f"未找到BUILD-MANIFEST：{release_root}")


def load_sha256sums(release_root: Path) -> Dict[str, str]:
    """加载SHA256SUMS文件"""
    sha256sums_path = release_root / "SHA256SUMS"
    if not sha256sums_path.exists():
        raise ValueError(f"SHA256SUMS文件不存在：{sha256sums_path}")

    expected_sums = {}
    with open(sha256sums_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                parts = line.split(None, 1)
                if len(parts) == 2:
                    sha_value, file_path = parts
                    if not validate_sha256(sha_value):
                        raise ValueError(f"SHA256SUMS中存在无效SHA256：{sha_value}")
                    clean_path = file_path.lstrip('./')
                    expected_sums[clean_path] = sha_value.lower()
    return expected_sums


def verify_release_artifacts(
    release_root: Path,
    manifest: Dict[str, Any],
    sha256sums: Dict[str, str]
) -> Tuple[str, List[Dict[str, str]]]:
    """验证release目录artifacts与SHA256SUMS一致，包括manifest自身jarSha256"""
    backend_jar_rel = manifest.get('backend_jar', 'backend/shenzhou-hr.jar')
    backend_jar = release_root / backend_jar_rel

    if not backend_jar.exists():
        raise ValueError(f"后端jar不存在：{backend_jar}")

    actual_jar_sha = compute_sha256(backend_jar)

    if backend_jar_rel not in sha256sums:
        raise ValueError(f"SHA256SUMS中未找到后端jar：{backend_jar_rel}")

    expected_jar_sha = sha256sums[backend_jar_rel]
    if actual_jar_sha.lower() != expected_jar_sha.lower():
        raise ValueError(
            f"后端jar SHA256不匹配\n"
            f"期望：{expected_jar_sha}\n实际：{actual_jar_sha}"
        )

    if 'jarSha256' in manifest:
        if actual_jar_sha.lower() != manifest['jarSha256'].lower():
            raise ValueError(
                f"后端jar SHA256与BUILD-MANIFEST.json中jarSha256不一致\n"
                f"BUILD-MANIFEST.json：{manifest['jarSha256']}\n"
                f"实际计算：{actual_jar_sha}"
            )

    manifest_files = ['BUILD-MANIFEST.json', 'BUILD-MANIFEST.txt']
    for mf in manifest_files:
        if (release_root / mf).exists():
            manifest_rel = mf
            if manifest_rel not in sha256sums:
                raise ValueError(f"SHA256SUMS缺少manifest文件：{manifest_rel}")
            actual_manifest_sha = compute_sha256(release_root / mf)
            if actual_manifest_sha.lower() != sha256sums[manifest_rel].lower():
                raise ValueError(
                    f"manifest文件SHA256不匹配\n"
                    f"文件：{manifest_rel}\n"
                    f"期望：{sha256sums[manifest_rel]}\n实际：{actual_manifest_sha}"
                )

    # 验证SHA256SUMS中所有列出的文件
    for file_rel_path, expected_sha in sha256sums.items():
        # 防止路径遍历攻击
        if '..' in file_rel_path or file_rel_path.startswith('/'):
            raise ValueError(f"SHA256SUMS包含非法路径：{file_rel_path}")

        file_path = release_root / file_rel_path
        if not file_path.exists():
            raise ValueError(f"SHA256SUMS列出的文件不存在：{file_rel_path}")
        if not file_path.is_file():
            continue

        actual_sha = compute_sha256(file_path)
        if actual_sha.lower() != expected_sha.lower():
            raise ValueError(
                f"文件SHA256不匹配\n"
                f"文件：{file_rel_path}\n"
                f"期望：{expected_sha}\n实际：{actual_sha}"
            )

    frontend_files = []
    frontend_dir_rel = manifest.get('frontend_dir', 'web')
    frontend_dir = release_root / frontend_dir_rel
    index_html_rel = f"{frontend_dir_rel}/index.html"
    index_html_path = release_root / index_html_rel

    # 常规发布包必须包含web目录和index.html
    if not frontend_dir.exists() or not frontend_dir.is_dir():
        raise ValueError(f"缺少必需的前端目录：{frontend_dir_rel}")
    if not index_html_path.exists():
        raise ValueError(f"前端目录缺少必需文件：{index_html_rel}")
    if index_html_rel not in sha256sums:
        raise ValueError(f"SHA256SUMS缺少index.html：{index_html_rel}")

    if frontend_dir.exists() and frontend_dir.is_dir():

        for file_rel_path, expected_sha in sha256sums.items():
            if file_rel_path.startswith(frontend_dir_rel + '/'):
                file_path = release_root / file_rel_path
                if file_path.is_file():
                    rel_to_frontend = file_path.relative_to(frontend_dir)
                    actual_sha = compute_sha256(file_path)
                    frontend_files.append({
                        'path': str(rel_to_frontend),
                        'sha256': actual_sha.lower()
                    })

    return actual_jar_sha.lower(), frontend_files


def verify_installed_artifacts(
    installed_jar: Path,
    installed_web: Optional[Path],
    expected_jar_sha: str,
    expected_frontend_files: List[Dict[str, str]]
) -> None:
    """验证已安装artifacts与release一致"""
    if not installed_jar.exists():
        raise ValueError(f"已安装jar不存在：{installed_jar}")

    actual_jar_sha = compute_sha256(installed_jar)
    if actual_jar_sha.lower() != expected_jar_sha.lower():
        raise ValueError(
            f"已安装jar SHA256不匹配\n"
            f"期望：{expected_jar_sha}\n实际：{actual_jar_sha}"
        )

    if installed_web and expected_frontend_files:
        if not installed_web.exists():
            raise ValueError(f"已安装web目录不存在：{installed_web}")

        for file_info in expected_frontend_files:
            file_path = installed_web / file_info['path']
            if not file_path.exists():
                raise ValueError(f"已安装前端文件缺失：{file_path}")

            actual_sha = compute_sha256(file_path)
            if actual_sha.lower() != file_info['sha256'].lower():
                raise ValueError(
                    f"已安装前端文件SHA256不匹配\n"
                    f"文件：{file_info['path']}\n"
                    f"期望：{file_info['sha256']}\n实际：{actual_sha}"
                )


def verify_runtime_process(
    pid: int,
    base_url: str,
    installed_jar: Path
) -> None:
    """验证运行时进程：PID、命令行精确匹配-jar参数、端口、健康检查"""
    try:
        os.kill(pid, 0)
    except OSError:
        raise ValueError(f"PID {pid} 进程不存在")

    jar_resolved = str(installed_jar.resolve())

    cmdline_parts = []
    try:
        with open(f'/proc/{pid}/cmdline', 'rb') as f:
            cmdline_bytes = f.read()
            cmdline_parts = cmdline_bytes.split(b'\x00')
            cmdline_parts = [part.decode('utf-8', errors='replace') for part in cmdline_parts if part]
    except FileNotFoundError:
        try:
            import shlex
            result = subprocess.run(
                ['ps', '-p', str(pid), '-o', 'command='],
                capture_output=True, text=True, timeout=5, check=True
            )
            cmdline_str = result.stdout.strip()
            try:
                cmdline_parts = shlex.split(cmdline_str)
            except ValueError:
                cmdline_parts = cmdline_str.split()
        except (subprocess.SubprocessError, FileNotFoundError) as e:
            raise ValueError(f"无法验证PID {pid} 命令行")

    jar_found = False
    for i, part in enumerate(cmdline_parts):
        if part == '-jar' and i + 1 < len(cmdline_parts):
            jar_arg = cmdline_parts[i + 1]
            try:
                jar_arg_resolved = str(Path(jar_arg).resolve())
                if jar_arg_resolved == jar_resolved:
                    jar_found = True
                    break
            except Exception:
                pass

    if not jar_found:
        raise ValueError(f"PID {pid} 未运行指定jar: {jar_resolved}")

    port_match = re.search(r':(\d+)', base_url)
    if not port_match:
        raise ValueError(f"无法从base_url提取端口：{base_url}")

    port = port_match.group(1)
    try:
        result = subprocess.run(
            ['which', 'lsof'],
            capture_output=True, timeout=2
        )
        if result.returncode != 0:
            raise ValueError("lsof不可用，无法验证端口")

        result = subprocess.run(
            ['lsof', '-iTCP:' + port, '-sTCP:LISTEN', '-t'],
            capture_output=True, text=True, timeout=5
        )
        listening_pids = [p.strip() for p in result.stdout.strip().split('\n') if p.strip()]
        if str(pid) not in listening_pids:
            raise ValueError(f"PID {pid} 未监听端口 {port}")
    except subprocess.TimeoutExpired:
        raise ValueError("验证端口超时")

    health_url = base_url.rstrip('/') + '/actuator/health'
    try:
        import urllib.request
        req = urllib.request.Request(health_url, method='GET')
        with urllib.request.urlopen(req, timeout=10) as response:
            if response.status != 200:
                raise ValueError(f"健康检查HTTP状态码：{response.status}")
            data = json.loads(response.read().decode('utf-8'))
            if data.get('status') != 'UP':
                raise ValueError(f"健康检查status不是UP：{data.get('status')}")
    except Exception as e:
        raise ValueError(f"健康检查失败：{e}")


def load_install_receipt(receipt_path: Path, expected_release_name: str) -> Dict[str, Any]:
    """加载INSTALL-RECEIPT.json并验证"""
    if not receipt_path.exists():
        raise ValueError(f"INSTALL-RECEIPT.json不存在：{receipt_path}")

    with open(receipt_path, 'r', encoding='utf-8') as f:
        receipt = json.load(f)

    required_fields = ['releaseName', 'installedAtUtc', 'jarSha256', 'pid', 'base']
    for field in required_fields:
        if field not in receipt:
            raise ValueError(f"INSTALL-RECEIPT.json缺少字段：{field}")

    if receipt['releaseName'] != expected_release_name:
        raise ValueError(
            f"INSTALL-RECEIPT.json中releaseName不匹配\n"
            f"期望：{expected_release_name}\n实际：{receipt['releaseName']}"
        )

    if not validate_sha256(receipt['jarSha256']):
        raise ValueError(f"INSTALL-RECEIPT.json中jarSha256格式无效：{receipt['jarSha256']}")

    parse_iso8601_with_tz(receipt['installedAtUtc'])
    return receipt


def generate_deployment_id(installed_at_dt: datetime) -> str:
    """生成稳定部署ID，基于UTC标准化的安装时间"""
    utc_dt = installed_at_dt.astimezone(timezone.utc)
    timestamp_clean = utc_dt.strftime('%Y%m%d-%H%M%S')
    ms_suffix = str(int(utc_dt.timestamp() * 1000))[-6:]
    return f"deploy-{timestamp_clean}-{ms_suffix}"


def compute_frontend_fingerprint(frontend_files: List[Dict[str, str]]) -> str:
    """计算frontend整体证据哈希"""
    if not frontend_files:
        return ""
    artifact_parts = []
    for f in sorted(frontend_files, key=lambda x: x['path']):
        artifact_parts.append(f"{f['path']}:{f['sha256']}")
    return hashlib.sha256('|'.join(artifact_parts).encode('utf-8')).hexdigest()[:16]


def load_history_locked(history_path: Path) -> Dict[str, Any]:
    """锁内加载并验证历史文件"""
    if not history_path.exists():
        return {"deployments": []}

    try:
        with open(history_path, 'r', encoding='utf-8') as f:
            content = f.read().strip()
            if not content:
                raise ValueError("历史文件为空")
            data = json.loads(content)
            if not isinstance(data, dict):
                raise ValueError("历史文件根元素不是对象")
            if "deployments" not in data:
                raise ValueError("历史文件缺少deployments字段")
            if not isinstance(data["deployments"], list):
                raise ValueError("历史文件deployments不是数组")

            for idx, entry in enumerate(data["deployments"]):
                if not isinstance(entry, dict):
                    raise ValueError(f"deployments[{idx}]不是对象")
                if not entry.get("deploymentId"):
                    raise ValueError(f"deployments[{idx}]缺少deploymentId")
                if not entry.get("deployedAt"):
                    raise ValueError(f"deployments[{idx}]缺少deployedAt")
                if not entry.get("version"):
                    raise ValueError(f"deployments[{idx}]缺少version")
            return data
    except json.JSONDecodeError as e:
        raise ValueError(f"历史文件JSON格式错误")


def save_history_atomic(history: Dict[str, Any], history_path: Path) -> None:
    """原子性保存历史：temp+fsync+replace+dir fsync"""
    history_path.parent.mkdir(parents=True, exist_ok=True)

    temp_fd, temp_path = tempfile.mkstemp(
        dir=history_path.parent,
        prefix='.history-',
        suffix='.json'
    )

    try:
        with os.fdopen(temp_fd, 'w', encoding='utf-8') as f:
            json.dump(history, f, indent=2, ensure_ascii=False)
            f.write('\n')
            f.flush()
            os.fsync(f.fileno())

        os.replace(temp_path, history_path)

        dir_fd = os.open(history_path.parent, os.O_RDONLY)
        try:
            os.fsync(dir_fd)
        finally:
            os.close(dir_fd)
    except Exception:
        try:
            os.unlink(temp_path)
        except:
            pass
        raise ValueError("保存历史文件失败")


def register_deployment(
    release_root: Path,
    title: str,
    description: str,
    details: str,
    history_path: Path,
    receipt_path: Optional[Path] = None,
    installed_jar: Optional[Path] = None,
    installed_web: Optional[Path] = None,
    installed_at: Optional[str] = None,
    verified_at: Optional[str] = None,
    runtime_verify: bool = True
) -> bool:
    """注册已验证的部署到历史"""
    try:
        manifest = load_manifest(release_root)
        sha256sums = load_sha256sums(release_root)
        version = manifest['release_name']

        jar_sha256, frontend_files = verify_release_artifacts(
            release_root, manifest, sha256sums
        )

        if runtime_verify:
            if not receipt_path:
                raise ValueError("Runtime模式需要提供--receipt参数")

            receipt = load_install_receipt(receipt_path, version)

            if not installed_jar:
                raise ValueError("Runtime模式需要提供--installed-jar参数")

            installed_jar_path = installed_jar
            installed_web_path = None

            # 如果有前端文件，必须提供installed_web或从receipt获取
            if frontend_files:
                if installed_web:
                    installed_web_path = installed_web
                elif 'web' in receipt and receipt['web']:
                    web_entries = receipt['web'] if isinstance(receipt['web'], list) else [receipt['web']]
                    if web_entries and isinstance(web_entries[0], dict) and 'root' in web_entries[0]:
                        installed_web_path = Path(web_entries[0]['root'])

                if not installed_web_path:
                    raise ValueError("Runtime模式检测到前端文件，但无法确定已安装web路径")

            if not receipt.get('jarSha256'):
                raise ValueError("INSTALL-RECEIPT.json缺少jarSha256字段")

            receipt_jar_sha = receipt['jarSha256'].lower()
            if receipt_jar_sha != jar_sha256:
                raise ValueError(
                    f"INSTALL-RECEIPT.json中jarSha256与release不一致\n"
                    f"receipt：{receipt_jar_sha}\n"
                    f"release：{jar_sha256}"
                )

            verify_installed_artifacts(
                installed_jar_path,
                installed_web_path,
                jar_sha256,
                frontend_files
            )

            verify_runtime_process(
                receipt['pid'],
                receipt['base'],
                installed_jar_path
            )

            installed_at_dt = parse_iso8601_with_tz(receipt['installedAtUtc'])
            installed_timestamp = installed_at_dt.isoformat()
            verified_timestamp = datetime.now(timezone.utc).isoformat()
        else:
            if not installed_at or not verified_at:
                raise ValueError("历史导入模式需要--installed-at和--verified-at")
            if not installed_jar:
                raise ValueError("历史导入模式需要--installed-jar")

            installed_at_dt = parse_iso8601_with_tz(installed_at)
            verified_at_dt = parse_iso8601_with_tz(verified_at)

            if installed_at_dt > verified_at_dt:
                raise ValueError("installed_at不能晚于verified_at")

            # 如果有前端文件，必须提供installed_web
            if frontend_files and not installed_web:
                raise ValueError("历史导入模式检测到前端文件，需要提供--installed-web")

            verify_installed_artifacts(
                installed_jar,
                installed_web,
                jar_sha256,
                frontend_files
            )

            installed_timestamp = installed_at_dt.isoformat()
            verified_timestamp = verified_at_dt.isoformat()

        deployment_id = generate_deployment_id(installed_at_dt)
        frontend_fingerprint = compute_frontend_fingerprint(frontend_files)

        lock_path = history_path.with_name('.' + history_path.name + '.lock')
        history_path.parent.mkdir(parents=True, exist_ok=True)

        with open(lock_path, 'w') as lock_file:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                history = load_history_locked(history_path)
                deployments = history.get("deployments", [])

                for entry in deployments:
                    if entry.get("deploymentId") == deployment_id:
                        entry_fingerprint = entry.get("_frontendFingerprint", "")
                        entry_jar = entry.get("jarSha256", "")
                        entry_version = entry.get("version", "")

                        if (entry_jar == jar_sha256 and
                            entry_version == version and
                            entry_fingerprint == frontend_fingerprint):
                            print(f"信息：部署 {deployment_id} 已注册（幂等）")
                            return True
                        else:
                            raise ValueError(
                                f"部署ID {deployment_id} 已存在但artifact不同"
                            )

                new_entry = {
                    "deploymentId": deployment_id,
                    "deployedAt": installed_timestamp,
                    "verifiedAt": verified_timestamp,
                    "version": version,
                    "jarSha256": jar_sha256,
                    "title": title,
                    "description": description,
                    "details": details,
                    "_frontendFingerprint": frontend_fingerprint,
                    "_receiptReleaseName": version
                }

                deployments.insert(0, new_entry)
                history["deployments"] = deployments

                save_history_atomic(history, history_path)

                print(f"成功：已注册部署 {version} (ID: {deployment_id})")
                print(f"  安装时间：{installed_timestamp}")
                print(f"  验证时间：{verified_timestamp}")
                return True

            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)

    except ValueError as e:
        print(f"错误：{e}", file=sys.stderr)
        return False
    except Exception as e:
        print(f"错误：{e}", file=sys.stderr)
        return False


def main():
    import argparse

    parser = argparse.ArgumentParser(
        description="注册已验证的部署到历史记录",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument("--release-root", required=True, type=Path,
                        help="Release根目录")
    parser.add_argument("--title", required=True, help="部署标题")
    parser.add_argument("--description", default="", help="简要描述")
    parser.add_argument("--details", default="", help="详细变更")
    parser.add_argument("--history", type=Path, default=Path(DEFAULT_HISTORY_PATH),
                        help=f"历史文件路径(默认: {DEFAULT_HISTORY_PATH})")

    parser.add_argument("--receipt", type=Path,
                        help="INSTALL-RECEIPT.json路径(runtime模式必需)")
    parser.add_argument("--installed-jar", type=Path,
                        help="已安装jar路径(runtime模式和历史导入必需)")
    parser.add_argument("--installed-web", type=Path,
                        help="已安装web目录(可选，优先级高于receipt.web)")

    parser.add_argument("--no-runtime-verify", action="store_true",
                        help="跳过runtime验证(历史导入)")
    parser.add_argument("--installed-at", help="安装时间(ISO 8601, 历史导入必需)")
    parser.add_argument("--verified-at", help="验证时间(ISO 8601, 历史导入必需)")

    args = parser.parse_args()

    success = register_deployment(
        release_root=args.release_root,
        title=args.title,
        description=args.description,
        details=args.details,
        history_path=args.history,
        receipt_path=args.receipt,
        installed_jar=args.installed_jar,
        installed_web=args.installed_web,
        installed_at=args.installed_at,
        verified_at=args.verified_at,
        runtime_verify=not args.no_runtime_verify
    )

    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
