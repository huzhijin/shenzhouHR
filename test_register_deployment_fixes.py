#!/usr/bin/env python3
"""
测试register-deployment.py的三项修复：
1. BUILD-MANIFEST.txt命名支持
2. web/index.html缺失检测和SHA256SUMS完整性验证
3. installed_web必需性检查
"""

import json
import tempfile
import hashlib
from pathlib import Path
from datetime import datetime, timezone
import sys
import subprocess

def compute_sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def create_txt_manifest_release():
    """创建使用BUILD-MANIFEST.txt的release目录"""
    tmpdir = Path(tempfile.mkdtemp())
    release_root = tmpdir / "release"
    release_root.mkdir()

    # 创建BUILD-MANIFEST.txt
    manifest_content = """release_name=v1.0.0-txt
backend_jar=backend/shenzhou-hr.jar
frontend_dir=web
"""
    manifest_path = release_root / "BUILD-MANIFEST.txt"
    manifest_path.write_text(manifest_content)

    # 创建后端jar
    backend_dir = release_root / "backend"
    backend_dir.mkdir()
    jar_content = b"fake-jar-content-txt"
    jar_path = backend_dir / "shenzhou-hr.jar"
    jar_path.write_bytes(jar_content)
    jar_sha = compute_sha256(jar_content)

    # 创建前端
    web_dir = release_root / "web"
    web_dir.mkdir()
    index_content = b"<html>txt</html>"
    index_path = web_dir / "index.html"
    index_path.write_bytes(index_content)
    index_sha = compute_sha256(index_content)

    # 创建SHA256SUMS（正确的文件名，无.txt）
    manifest_sha = compute_sha256(manifest_content.encode('utf-8'))
    sha256sums_content = f"""{manifest_sha}  BUILD-MANIFEST.txt
{jar_sha}  backend/shenzhou-hr.jar
{index_sha}  web/index.html
"""
    sha256sums_path = release_root / "SHA256SUMS"
    sha256sums_path.write_text(sha256sums_content)

    return tmpdir, release_root, jar_sha, jar_path

def create_missing_index_release():
    """创建缺少web/index.html的release"""
    tmpdir = Path(tempfile.mkdtemp())
    release_root = tmpdir / "release"
    release_root.mkdir()

    manifest = {
        "releaseName": "v1.0.0-no-index",
        "backend_jar": "backend/shenzhou-hr.jar",
        "frontend_dir": "web"
    }
    manifest_path = release_root / "BUILD-MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest))

    backend_dir = release_root / "backend"
    backend_dir.mkdir()
    jar_content = b"fake-jar-no-index"
    jar_path = backend_dir / "shenzhou-hr.jar"
    jar_path.write_bytes(jar_content)
    jar_sha = compute_sha256(jar_content)

    # 创建web目录但不创建index.html
    web_dir = release_root / "web"
    web_dir.mkdir()

    manifest_sha = compute_sha256(json.dumps(manifest).encode('utf-8'))
    sha256sums_content = f"""{manifest_sha}  BUILD-MANIFEST.json
{jar_sha}  backend/shenzhou-hr.jar
"""
    sha256sums_path = release_root / "SHA256SUMS"
    sha256sums_path.write_text(sha256sums_content)

    return tmpdir, release_root

def create_incomplete_sha256sums_release():
    """创建SHA256SUMS中列出但未验证所有文件的release"""
    tmpdir = Path(tempfile.mkdtemp())
    release_root = tmpdir / "release"
    release_root.mkdir()

    manifest = {
        "releaseName": "v1.0.0-incomplete-sums"
    }
    manifest_path = release_root / "BUILD-MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest))

    backend_dir = release_root / "backend"
    backend_dir.mkdir()
    jar_content = b"fake-jar-incomplete"
    jar_path = backend_dir / "shenzhou-hr.jar"
    jar_path.write_bytes(jar_content)
    jar_sha = compute_sha256(jar_content)

    # 创建额外的配置文件
    config_content = b"config-data"
    config_path = release_root / "config.yml"
    config_path.write_bytes(config_content)
    config_sha = compute_sha256(config_content)

    manifest_sha = compute_sha256(json.dumps(manifest).encode('utf-8'))
    # SHA256SUMS列出config.yml但内容不匹配
    sha256sums_content = f"""{manifest_sha}  BUILD-MANIFEST.json
{jar_sha}  backend/shenzhou-hr.jar
{'a' * 64}  config.yml
"""
    sha256sums_path = release_root / "SHA256SUMS"
    sha256sums_path.write_text(sha256sums_content)

    return tmpdir, release_root

def test_txt_manifest_support():
    """测试1：BUILD-MANIFEST.txt命名支持"""
    print("测试1：BUILD-MANIFEST.txt命名支持...", end=" ")
    tmpdir, release_root, jar_sha, jar_path = create_txt_manifest_release()

    try:
        # 直接导入模块函数
        script_path = Path(__file__).parent / "register-deployment.py"
        import importlib.util
        spec = importlib.util.spec_from_file_location("register_deployment", script_path)
        rd = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(rd)

        manifest = rd.load_manifest(release_root)
        assert manifest['release_name'] == 'v1.0.0-txt'
        assert manifest['backend_jar'] == 'backend/shenzhou-hr.jar'
        assert manifest['frontend_dir'] == 'web'

        sha256sums = rd.load_sha256sums(release_root)
        actual_jar_sha, frontend_files = rd.verify_release_artifacts(
            release_root, manifest, sha256sums
        )
        assert actual_jar_sha == jar_sha
        assert len(frontend_files) == 1
        assert frontend_files[0]['path'] == 'index.html'

        print("✓ 通过")
        return True
    except Exception as e:
        print(f"✗ 失败: {e}")
        return False
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)

def test_missing_index_detection():
    """测试2：web/index.html缺失检测"""
    print("测试2：web/index.html缺失检测...", end=" ")
    tmpdir, release_root = create_missing_index_release()

    try:
        script_path = Path(__file__).parent / "register-deployment.py"
        import importlib.util
        spec = importlib.util.spec_from_file_location("register_deployment", script_path)
        rd = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(rd)

        manifest = rd.load_manifest(release_root)
        sha256sums = rd.load_sha256sums(release_root)

        try:
            rd.verify_release_artifacts(release_root, manifest, sha256sums)
            print("✗ 失败: 应该检测到index.html缺失")
            return False
        except ValueError as e:
            if 'index.html' in str(e):
                print("✓ 通过")
                return True
            else:
                print(f"✗ 失败: 错误信息不正确: {e}")
                return False
    except Exception as e:
        print(f"✗ 失败: {e}")
        return False
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)

def test_sha256sums_complete_verification():
    """测试3：SHA256SUMS完整性验证（所有列出的文件都需验证）"""
    print("测试3：SHA256SUMS完整性验证...", end=" ")
    tmpdir, release_root = create_incomplete_sha256sums_release()

    try:
        script_path = Path(__file__).parent / "register-deployment.py"
        import importlib.util
        spec = importlib.util.spec_from_file_location("register_deployment", script_path)
        rd = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(rd)

        manifest = rd.load_manifest(release_root)
        sha256sums = rd.load_sha256sums(release_root)

        try:
            rd.verify_release_artifacts(release_root, manifest, sha256sums)
            print("✗ 失败: 应该检测到config.yml哈希不匹配")
            return False
        except ValueError as e:
            if 'config.yml' in str(e) and 'SHA256' in str(e):
                print("✓ 通过")
                return True
            else:
                print(f"✗ 失败: 错误信息不正确: {e}")
                return False
    except Exception as e:
        print(f"✗ 失败: {e}")
        return False
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)

def test_installed_web_required():
    """测试4：有前端文件时必须提供installed_web"""
    print("测试4：installed_web必需性检查...", end=" ")
    tmpdir, release_root, jar_sha, jar_path = create_txt_manifest_release()

    try:
        script_path = Path(__file__).parent / "register-deployment.py"
        import importlib.util
        spec = importlib.util.spec_from_file_location("register_deployment", script_path)
        rd = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(rd)

        # 创建安装路径但不提供installed_web
        installed_jar = tmpdir / "installed.jar"
        installed_jar.write_bytes(jar_path.read_bytes())

        history_path = tmpdir / "history.json"

        # 历史导入模式，有前端文件但不提供installed_web
        success = rd.register_deployment(
            release_root=release_root,
            title="测试",
            description="",
            details="",
            history_path=history_path,
            installed_jar=installed_jar,
            installed_web=None,
            installed_at="2026-10-12T10:00:00+08:00",
            verified_at="2026-10-12T10:05:00+08:00",
            runtime_verify=False
        )

        if not success:
            # register_deployment返回False表示验证失败，这是预期的
            print("✓ 通过")
            return True
        else:
            print("✗ 失败: 应该要求提供installed_web")
            return False
    except Exception as e:
        print(f"✗ 失败: {e}")
        return False
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)

def test_missing_web_directory():
    """测试5：缺少web目录时必须失败"""
    print("测试5：缺少web目录检测...", end=" ")
    tmpdir = Path(tempfile.mkdtemp())
    release_root = tmpdir / "release"
    release_root.mkdir()

    try:
        # 创建BUILD-MANIFEST.json
        manifest = {
            "releaseName": "v1.0.0-no-web",
            "backend_jar": "backend/shenzhou-hr.jar",
            "frontend_dir": "web"
        }
        manifest_path = release_root / "BUILD-MANIFEST.json"
        manifest_path.write_text(json.dumps(manifest))

        # 创建后端jar
        backend_dir = release_root / "backend"
        backend_dir.mkdir()
        jar_content = b"fake-jar-no-web"
        jar_path = backend_dir / "shenzhou-hr.jar"
        jar_path.write_bytes(jar_content)
        jar_sha = compute_sha256(jar_content)

        # 不创建web目录
        manifest_sha = compute_sha256(json.dumps(manifest).encode('utf-8'))
        sha256sums_content = f"""{manifest_sha}  BUILD-MANIFEST.json
{jar_sha}  backend/shenzhou-hr.jar
"""
        sha256sums_path = release_root / "SHA256SUMS"
        sha256sums_path.write_text(sha256sums_content)

        script_path = Path(__file__).parent / "register-deployment.py"
        import importlib.util
        spec = importlib.util.spec_from_file_location("register_deployment", script_path)
        rd = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(rd)

        manifest = rd.load_manifest(release_root)
        sha256sums = rd.load_sha256sums(release_root)

        try:
            rd.verify_release_artifacts(release_root, manifest, sha256sums)
            print("✗ 失败: 应该检测到web目录缺失")
            return False
        except ValueError as e:
            if 'web' in str(e).lower() and ('缺少' in str(e) or '目录' in str(e)):
                print("✓ 通过")
                return True
            else:
                print(f"✗ 失败: 错误信息不正确: {e}")
                return False
    except Exception as e:
        print(f"✗ 失败: {e}")
        return False
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)

def main():
    print("=" * 60)
    print("register-deployment.py 修复验证测试")
    print("=" * 60)

    results = []
    results.append(test_txt_manifest_support())
    results.append(test_missing_index_detection())
    results.append(test_sha256sums_complete_verification())
    results.append(test_installed_web_required())
    results.append(test_missing_web_directory())

    print("=" * 60)
    passed = sum(results)
    total = len(results)
    print(f"测试结果: {passed}/{total} 通过")
    print("=" * 60)

    return 0 if all(results) else 1

if __name__ == "__main__":
    sys.exit(main())
