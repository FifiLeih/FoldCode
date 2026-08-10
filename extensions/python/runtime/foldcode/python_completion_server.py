"""FoldCode Python extension completion and diagnostics service.

The host speaks newline-delimited JSON. Jedi/Parso and Python's AST inspect
source without executing the edited project, so analysis remains safe/offline.
"""

import ast
import builtins
import json
import keyword
import os
import re
import sys

try:
    import jedi
except ImportError:  # Keep MicroPython/static fallback usable in partial packages.
    jedi = None

if jedi is not None:
    cache_directory = os.environ.get("FOLDCODE_JEDI_CACHE")
    if cache_directory:
        os.makedirs(cache_directory, exist_ok=True)
        jedi.settings.cache_directory = cache_directory


STANDARD_MODULES = (
    "argparse asyncio collections csv datetime functools hashlib http importlib "
    "itertools json logging math os pathlib random re shutil sqlite3 ssl statistics "
    "subprocess sys tempfile threading time typing unittest urllib "
    "machine micropython network rp2 uasyncio ubluetooth ucryptolib uctypes"
).split()

MODULE_MEMBERS = {
    "os": {"environ": "mapping", "getcwd": "function", "listdir": "function", "makedirs": "function", "path": "module", "remove": "function", "rename": "function", "walk": "function"},
    "os.path": {"abspath": "function", "basename": "function", "dirname": "function", "exists": "function", "isdir": "function", "isfile": "function", "join": "function", "splitext": "function"},
    "json": {"dump": "function", "dumps": "function", "load": "function", "loads": "function", "JSONDecodeError": "exception"},
    "math": {"ceil": "function", "cos": "function", "e": "constant", "floor": "function", "pi": "constant", "sin": "function", "sqrt": "function", "tan": "function"},
    "pathlib": {"Path": "class", "PurePath": "class", "PurePosixPath": "class"},
    "re": {"compile": "function", "findall": "function", "match": "function", "search": "function", "split": "function", "sub": "function"},
    "sys": {"argv": "list", "exit": "function", "path": "list", "platform": "string", "stderr": "stream", "stdin": "stream", "stdout": "stream", "version": "string"},
    "asyncio": {"create_task": "function", "gather": "function", "get_event_loop": "function", "run": "function", "sleep": "function"},
    "machine": {"ADC": "class", "I2C": "class", "Pin": "class", "PWM": "class", "RTC": "class", "SPI": "class", "Timer": "class", "UART": "class", "WDT": "class", "deepsleep": "function", "freq": "function", "idle": "function", "lightsleep": "function", "reset": "function", "unique_id": "function"},
    "machine.Pin": {"IN": "constant", "OUT": "constant", "OPEN_DRAIN": "constant", "PULL_DOWN": "constant", "PULL_UP": "constant", "IRQ_FALLING": "constant", "IRQ_RISING": "constant", "high": "function", "irq": "function", "low": "function", "off": "function", "on": "function", "toggle": "function", "value": "function"},
    "machine.ADC": {"read_u16": "function"},
    "machine.I2C": {"readfrom": "function", "readfrom_mem": "function", "scan": "function", "writeto": "function", "writeto_mem": "function"},
    "machine.PWM": {"deinit": "function", "duty_ns": "function", "duty_u16": "function", "freq": "function"},
    "machine.SPI": {"init": "function", "read": "function", "readinto": "function", "write": "function", "write_readinto": "function"},
    "machine.Timer": {"ONE_SHOT": "constant", "PERIODIC": "constant", "deinit": "function", "init": "function"},
    "machine.UART": {"any": "function", "flush": "function", "init": "function", "read": "function", "readinto": "function", "readline": "function", "write": "function"},
    "rp2": {"PIO": "class", "StateMachine": "class", "asm_pio": "function", "bootsel_button": "function", "country": "function", "dht_readinto": "function", "flash_user_start": "function"},
    "rp2.StateMachine": {"active": "function", "exec": "function", "get": "function", "irq": "function", "put": "function", "restart": "function", "rx_fifo": "function", "tx_fifo": "function"},
    "network": {"AP_IF": "constant", "STA_IF": "constant", "WLAN": "class", "country": "function", "hostname": "function"},
    "network.WLAN": {"active": "function", "config": "function", "connect": "function", "disconnect": "function", "ifconfig": "function", "isconnected": "function", "scan": "function", "status": "function"},
    "micropython": {"alloc_emergency_exception_buf": "function", "const": "function", "kbd_intr": "function", "mem_info": "function", "opt_level": "function", "qstr_info": "function", "schedule": "function", "stack_use": "function"},
}


def item(label, insertion=None, detail="Python symbol"):
    return {"label": label, "insertion": insertion or label, "detail": detail}


def source_position(source, cursor):
    before = source[: max(0, min(cursor, len(source)))]
    return before.count("\n") + 1, len(before.rsplit("\n", 1)[-1])


def project_script(source, relative_file, project_root):
    if jedi is None:
        return None
    path = os.path.join(project_root, relative_file or "main.py")
    added = [project_root, os.path.join(project_root, ".foldcode", "python")]
    project = jedi.Project(project_root, added_sys_path=added)
    return jedi.Script(code=source, path=path, project=project)


def complete(source, cursor, project_files, relative_file, project_root):
    before = source[: max(0, min(cursor, len(source)))]
    imports = {}
    receiver_types = {}
    symbols = []

    # Jedi uses Parso's error-recovering parser, so completion remains useful
    # while a line is incomplete. It also understands imports, annotations,
    # inferred receiver types and packages installed inside the project.
    script = None
    try:
        script = project_script(source, relative_file, project_root)
        if script is not None:
            line, column = source_position(source, cursor)
            for value in script.complete(line, column):
                label = value.name
                detail = value.description or ("Python " + value.type)
                symbols.append(item(label, label, detail))
    except Exception:
        # A third-party Jedi plugin or malformed source must not kill the
        # persistent service. The safe built-in/MicroPython fallback follows.
        script = None

    try:
        tree = ast.parse(source)
    except SyntaxError:
        tree = None
    if tree is not None:
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                symbols.append(item(node.name, node.name + "(", "project function"))
            elif isinstance(node, ast.ClassDef):
                symbols.append(item(node.name, node.name + "(", "project class"))
            elif isinstance(node, (ast.Assign, ast.AnnAssign)):
                targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                for target in targets:
                    if isinstance(target, ast.Name):
                        symbols.append(item(target.id, detail="project variable"))
                        if isinstance(node, ast.Assign) and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name):
                            receiver_types[target.id] = imports.get(node.value.func.id, node.value.func.id)
            elif isinstance(node, ast.Import):
                for alias in node.names:
                    local = alias.asname or alias.name.split(".", 1)[0]
                    imports[local] = alias.name
                    symbols.append(item(local, detail="module " + alias.name))
            elif isinstance(node, ast.ImportFrom):
                for alias in node.names:
                    local = alias.asname or alias.name
                    imports[local] = (node.module + "." + alias.name) if node.module else alias.name
                    symbols.append(item(local, detail="imported symbol"))

    # Preserve import/type inference while the current statement is unfinished
    # and therefore cannot be parsed by CPython's strict AST parser.
    for match in re.finditer(r"(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?", source):
        module, alias = match.groups()
        imports.setdefault(alias or module.split(".", 1)[0], module)
    for match in re.finditer(r"(?m)^\s*from\s+([A-Za-z_][A-Za-z0-9_.]*)\s+import\s+([A-Za-z_][A-Za-z0-9_]*)", source):
        module, name = match.groups()
        imports.setdefault(name, module + "." + name)
    for match in re.finditer(r"(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(", source):
        receiver, constructor = match.groups()
        receiver_types.setdefault(receiver, imports.get(constructor, constructor))

    receiver_match = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\.[A-Za-z0-9_]*$", before)
    if receiver_match:
        receiver = receiver_match.group(1)
        module = receiver_types.get(receiver, imports.get(receiver, receiver))
        members = MODULE_MEMBERS.get(module)
        if members:
            symbols.extend(
                item(name, name + "(" if kind == "function" else name, module + " · " + kind)
                for name, kind in members.items()
            )

    symbols.extend(item(name, name + "(", "Python built-in") for name in dir(builtins) if not name.startswith("__"))
    symbols.extend(item(name, detail="Python keyword") for name in keyword.kwlist + ["match", "case"])
    symbols.extend(item(name, detail="Python standard-library module") for name in STANDARD_MODULES)
    for path in project_files:
        if path.lower().endswith(".py"):
            module = path[:-3].replace("/", ".").removesuffix(".__init__")
            if module:
                symbols.append(item(module, detail="project module"))

    unique = {}
    for candidate in symbols:
        unique.setdefault(candidate["label"], candidate)
    return list(unique.values())


def diagnose(source, relative_file, project_root):
    diagnostics = []
    try:
        script = project_script(source, relative_file, project_root)
        errors = script.get_syntax_errors() if script is not None else []
        for error in errors:
            message = error.get_message() if hasattr(error, "get_message") else str(error)
            diagnostics.append({
                "file": relative_file,
                "line": max(1, int(getattr(error, "line", 1))),
                "column": max(1, int(getattr(error, "column", 0)) + 1),
                "severity": "error",
                "message": message,
            })
    except Exception:
        pass

    # Jedi intentionally reports syntax rather than runtime errors. CPython's
    # compiler is a reliable fallback when Jedi is unavailable or returns no
    # location for indentation/encoding failures.
    if not diagnostics:
        try:
            compile(source, relative_file or "main.py", "exec", dont_inherit=True)
        except (SyntaxError, IndentationError, TabError) as error:
            diagnostics.append({
                "file": relative_file,
                "line": max(1, int(error.lineno or 1)),
                "column": max(1, int(error.offset or 1)),
                "severity": "error",
                "message": error.msg,
            })
    if not diagnostics:
        # A deliberately conservative unresolved-name pass supplies useful
        # editor feedback without executing the project. Collecting names for
        # the whole module avoids noisy control-flow and forward-reference
        # false positives; language servers can become stricter later.
        try:
            tree = ast.parse(source, filename=relative_file or "main.py")
            declared = set(dir(builtins)) | {
                "__name__", "__file__", "__package__", "__spec__",
                "__annotations__", "__builtins__",
            }
            wildcard_import = False
            for node in ast.walk(tree):
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                    declared.add(node.name)
                elif isinstance(node, ast.arg):
                    declared.add(node.arg)
                elif isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Param)):
                    declared.add(node.id)
                elif isinstance(node, ast.Import):
                    for alias in node.names:
                        declared.add(alias.asname or alias.name.split(".", 1)[0])
                elif isinstance(node, ast.ImportFrom):
                    for alias in node.names:
                        if alias.name == "*":
                            wildcard_import = True
                        else:
                            declared.add(alias.asname or alias.name)
                elif isinstance(node, ast.ExceptHandler) and node.name:
                    declared.add(node.name)

            if not wildcard_import:
                reported = set()
                for node in ast.walk(tree):
                    if not isinstance(node, ast.Name) or not isinstance(node.ctx, ast.Load):
                        continue
                    if node.id in declared or node.id in reported:
                        continue
                    reported.add(node.id)
                    diagnostics.append({
                        "file": relative_file,
                        "line": max(1, int(getattr(node, "lineno", 1))),
                        "column": max(1, int(getattr(node, "col_offset", 0)) + 1),
                        "severity": "error",
                        "message": "Undefined name '%s'" % node.id,
                    })
        except (SyntaxError, ValueError, TypeError):
            pass
    return diagnostics


for raw in sys.stdin:
    try:
        request = json.loads(raw)
        operation = request.get("operation", "complete")
        project_root = os.getcwd()
        if operation == "diagnose":
            response = {
                "id": request["id"],
                "operation": operation,
                "diagnostics": diagnose(
                    request.get("source", ""), request.get("relativeFile", "main.py"), project_root
                ),
            }
        else:
            response = {
                "id": request["id"],
                "operation": operation,
                "items": complete(
                    request.get("source", ""),
                    int(request.get("cursor", 0)),
                    request.get("projectFiles", []),
                    request.get("relativeFile", "main.py"),
                    project_root,
                ),
            }
    except Exception as error:  # Keep the extension service alive after malformed input.
        response = {"id": None, "operation": "error", "items": [], "diagnostics": [], "error": str(error)}
    print(json.dumps(response, ensure_ascii=False), flush=True)
