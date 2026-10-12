"""Check main/test sources with the Java extension's Eclipse compiler.

Run Maven tests first to provide the resolved test classpath in Surefire XML.
Usage: python scripts/verify-java-diagnostics.py [--ecj /path/to/compiler.jar]
"""

import argparse
import locale
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ecj", type=Path)
    options = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    compiler = options.ecj
    if compiler is None:
        candidates = list((Path.home() / ".vscode/extensions").glob(
            "redhat.java-*/server/plugins/org.eclipse.jdt.core.compiler.batch_*.jar"))
        if not candidates:
            parser.error("Java extension compiler not found; specify --ecj")
        compiler = max(candidates, key=lambda path: path.stat().st_mtime)
    reports = list((root / "backend/target/surefire-reports").glob("TEST-*.xml"))
    if not reports:
        parser.error("Run Maven tests first; Surefire reports are required")
    report = max(reports, key=lambda path: path.stat().st_mtime)
    properties = ET.parse(report).findall("./properties/property")
    classpath = next((p.get("value") for p in properties
                      if p.get("name") == "java.class.path"), None)
    if not classpath:
        parser.error("Surefire report has no resolved Java classpath")
    # All project sources compile together; only dependency jars are needed.
    import os
    dependencies = [path for path in classpath.split(os.pathsep) if path.endswith(".jar")]
    if not dependencies or any(not Path(path).is_file() for path in dependencies):
        parser.error("Resolved dependencies are missing; rerun Maven tests")
    output = root / ".cache/java-diagnostics"
    output.mkdir(parents=True, exist_ok=True)
    settings = output / "null-analysis.properties"
    settings.write_text("\n".join([
        "org.eclipse.jdt.core.compiler.annotation.nullanalysis=enabled",
        "org.eclipse.jdt.core.compiler.annotation.nonnull=org.springframework.lang.NonNull",
        "org.eclipse.jdt.core.compiler.annotation.nullable=org.springframework.lang.Nullable",
        "org.eclipse.jdt.core.compiler.annotation.nonnullbydefault=org.springframework.lang.NonNullApi",
        *[f"org.eclipse.jdt.core.compiler.problem.{name}=warning" for name in [
            "nullReference", "potentialNullReference", "nullSpecViolation",
            "nullUncheckedConversion", "nonnullParameterAnnotationDropped", "unusedImport"]],
    ]) + "\n", encoding="utf-8")
    sources = sorted((root / "backend/src").rglob("*.java"))
    arguments = output / "arguments.txt"
    arguments.write_text("\n".join([
        "-21", "-encoding UTF-8", "-proc:none", "-classpath",
        '"' + os.pathsep.join(dependencies).replace("\\", "/") + '"',
        "-d", '"' + (output / "classes").as_posix() + '"',
        "-properties", '"' + settings.as_posix() + '"',
        *['"' + path.as_posix() + '"' for path in sources],
    ]) + "\n", encoding="utf-8")
    result = subprocess.run(["java", "-jar", str(compiler), "@" + str(arguments)],
                            capture_output=True)
    log = (result.stdout + result.stderr).decode(locale.getpreferredencoding(False), errors="replace")
    (output / "compiler.log").write_text(log, encoding="utf-8")
    diagnostics = len(re.findall(r"\d+\. (?:WARNING|ERROR) in ", log))
    print(f"Checked {len(sources)} Java files; {diagnostics} diagnostics; exit {result.returncode}")
    print(f"Evidence: {output / 'compiler.log'}")
    if result.returncode or diagnostics:
        print(log)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
