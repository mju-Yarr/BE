#!/usr/bin/env python3
"""Generate doc/API.md from Spring MVC controllers and DTO source.

This is deliberately dependency-free: it is a guardrail against route/DTO drift, not a
replacement for an OpenAPI runtime schema. Run from the repository root.
"""
from __future__ import annotations

import argparse
import re
import subprocess
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/hq/backend"
OUT = ROOT / "doc/API.md"
METHODS = {"Get": "GET", "Post": "POST", "Put": "PUT", "Patch": "PATCH", "Delete": "DELETE"}
HTTP_STATUS = {
    "OK": "200 OK", "CREATED": "201 CREATED", "ACCEPTED": "202 ACCEPTED",
    "NO_CONTENT": "204 NO_CONTENT",
}


def balanced(text: str, open_at: int) -> tuple[str, int]:
    depth = 0
    for i in range(open_at, len(text)):
        if text[i] == "(": depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0: return text[open_at + 1:i], i + 1
    raise ValueError("unbalanced parentheses")


def split_top(text: str) -> list[str]:
    out, start, depth = [], 0, 0
    for i, c in enumerate(text + ","):
        if c in "(<[": depth += 1
        elif c in ")>]": depth -= 1
        elif c == "," and depth == 0:
            value = text[start:i].strip()
            if value: out.append(value)
            start = i + 1
    return out


def status_between(mapping_end: int, method_start: int, text: str) -> str:
    annotations = text[mapping_end:method_start]
    found = re.findall(r"@ResponseStatus\(HttpStatus\.([A-Z_]+)\)", annotations)
    return HTTP_STATUS.get(found[-1], found[-1]) if found else HTTP_STATUS["OK"]


def type_name(param: str) -> str:
    cleaned = re.sub(r"@[\w.]+(?:\([^)]*\))?", "", param)
    return " ".join(cleaned.split()).rsplit(" ", 1)[0]


def variable_name(param: str) -> str:
    cleaned = re.sub(r"@[\w.]+(?:\([^)]*\))?", "", param)
    return " ".join(cleaned.split()).rsplit(" ", 1)[-1]


def mapping_value(annotation: str) -> str:
    value = re.search(r'"([^"]*)"', annotation)
    return value.group(1) if value else ""


def find_endpoints() -> list[dict]:
    endpoints = []
    for file in sorted(JAVA.rglob("*Controller.java")):
        text = file.read_text()
        # only a class-level RequestMapping before its class declaration is relevant.
        before_class = text[:text.find("class ")]
        bases = re.findall(r'@RequestMapping\(([^)]*)\)', before_class)
        base = mapping_value(bases[-1]) if bases else ""
        pattern = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\((.*?)\))?', re.S)
        for match in pattern.finditer(text):
            method = METHODS[match.group(1)]
            suffix = mapping_value(match.group(2) or "")
            tail = text[match.end():]
            public = re.search(r'public\s+([\w<>?, .]+?)\s+(\w+)\s*\(', tail)
            if not public: continue
            signature_open = match.end() + public.end() - 1
            args, _ = balanced(text, signature_open)
            params, body = [], None
            auth = "Bearer JWT" if "@CurrentUserId" in args else "Public"
            for param in split_top(args):
                if "@CurrentUserId" in param: continue
                source = "body" if "@RequestBody" in param else "path" if "@PathVariable" in param else "query" if "@RequestParam" in param else "header" if "@RequestHeader" in param else "parameter"
                name_match = re.search(r'@(?:RequestParam|PathVariable|RequestHeader)\((?:value\s*=\s*)?"([^"]+)"', param)
                entry = {"in": source, "name": name_match.group(1) if name_match else variable_name(param), "type": type_name(param)}
                params.append(entry)
                if source == "body": body = entry["type"].replace("@Valid ", "")
            response = public.group(1).strip()
            if response.startswith("ResponseEntity<"):
                response = response[len("ResponseEntity<"):-1]
            endpoints.append({"method": method, "path": "/v1" + base + suffix, "handler": public.group(2), "auth": auth, "params": params, "body": body, "response": response, "status": status_between(match.end(), match.end() + public.start(), text), "controller": file.name})
    return endpoints


def constraints(value: str) -> str:
    found = re.findall(r'@(NotNull|NotBlank|NotEmpty|Email|Size|Pattern|Min|Max|Positive|PositiveOrZero|Past|Future)(?:\(([^)]*)\))?', value)
    return "; ".join(name + (f"({args})" if args else "") for name, args in found) or "—"


def find_dto_source(name: str, owner: str | None = None) -> str | None:
    """Source of a referenced type.

    Some type names exist in more than one package (ActionType lives in both preprule and
    event). Picking whichever file rglob yields first silently documents the wrong enum, so
    when the referencing DTO is known its import statement decides.
    """
    paths = sorted(JAVA.rglob(f"{name}.java"))
    if not paths: return None
    if len(paths) > 1 and owner:
        owner_text = next((p.read_text() for p in sorted(JAVA.rglob(f"{owner}.java"))), "")
        for path in paths:
            package = path.relative_to(JAVA).parent.as_posix().replace("/", ".")
            if re.search(r'^import\s+com\.hq\.backend\.' + re.escape(package) + r'\.' + re.escape(name) + r'\s*;',
                         owner_text, re.M):
                return path.read_text()
    return paths[0].read_text()


def dto_fields(name: str) -> list[tuple[str, str, str]]:
    text = find_dto_source(name)
    if not text: return []
    record = re.search(r'\brecord\s+' + re.escape(name) + r'\s*\(', text)
    if record:
        values, _ = balanced(text, record.end() - 1)
        result = []
        for field in split_top(values):
            clean = re.sub(r'@[\w.]+(?:\([^)]*\))?', '', field)
            words = clean.split()
            if len(words) >= 2: result.append((words[-1], " ".join(words[:-1]), constraints(field)))
        return result
    # DTO classes generally use private fields; expose source-backed fields even when no record exists.
    result = []
    for line in text.splitlines():
        m = re.search(r'((?:@\w+(?:\([^)]*\))?\s+)*)private\s+([\w<>?, ]+)\s+(\w+)\s*;', line)
        if m: result.append((m.group(3), " ".join(m.group(2).split()), constraints(m.group(1))))
    return result


def enum_values(name: str, owner: str | None = None) -> list[str]:
    """Wire values for an enum type, in declaration order.

    The constant block is delimited by the enum's own brace, not by the first ";" in the
    file -- that one belongs to the package/import lines and used to truncate the slice to
    nothing, which silently dropped every enum from the generated spec.
    """
    text = find_dto_source(name, owner)
    if not text: return []
    header = re.search(r'\benum\s+' + re.escape(name) + r'\b[^{]*\{', text)
    if not header: return []
    start = header.end()
    stops = [i for i in (text.find(";", start), text.find("}", start)) if i != -1]
    if not stops: return []
    body = re.sub(r'//[^\n]*|/\*.*?\*/', '', text[start:min(stops)], flags=re.S)
    values = re.findall(r'\b([A-Z][A-Z0-9_]*)\b', body)
    # @JsonValue enums serialize via name().toLowerCase(); the rest fall back to name().
    return [v.lower() for v in values] if "@JsonValue" in text else values


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the source-backed API reference")
    parser.add_argument("--check", action="store_true", help="fail if doc/API.md is stale")
    check = parser.parse_args().check
    endpoints = find_endpoints()
    sha = subprocess.check_output(
        ["git", "log", "-1", "--format=%H", "--", "src/main/java"], cwd=ROOT, text=True
    ).strip()
    dto_names = set()
    for e in endpoints:
        if e["body"]: dto_names.add(e["body"])
        response = re.sub(r'^(?:List|Set|Page)<(.+)>$', r'\1', e["response"])
        if response and response not in {"void", "String", "Object", "Map<String, String>"}: dto_names.add(response)
    lines = [
        "# ENSOM Backend API 명세서", "",
        f"> **Source of truth:** BE commit `{sha}`. 이 문서는 `tools/generate_api_spec.py`가 Spring MVC controller, DTO, Bean Validation, Jackson enum annotation에서 생성한다.",
        "> 생성: `python3 tools/generate_api_spec.py` · endpoint count 검증: `python3 tools/generate_api_spec.py --check`.", "",
        "## 1. 적용 범위와 전송 규약", "",
        "- Public base URL: `https://api.ensom.shop/v1`. nginx가 `/v1`을 제거해 Spring controller로 전달한다.",
        "- 요청/응답은 별도 표기가 없으면 JSON (`application/json`)이다. UUID는 RFC 4122 문자열, 시간은 ISO-8601 (`Instant`/`OffsetDateTime`), 날짜는 `YYYY-MM-DD` (`LocalDate`)다.",
        "- `Bearer JWT` endpoint는 `Authorization: Bearer <access-token>`이 필수다. `/auth/**`와 health endpoint만 public이며, 그 외 token 누락/무효는 `401 UNAUTHENTICATED`다.",
        "- Enum은 아래 schema 표의 wire value를 사용한다. `@JsonValue` enum은 lower-snake-case로 직렬화된다.",
        "- 이 문서는 controller source에서 생성되므로 구현된 endpoint만 담는다. 여기 없는 경로는 아직 없는 경로다.", "",
        "## 2. 오류 응답", "", "`ApiException` 및 validation 오류는 아래 envelope를 쓴다.", "", "```json", '{"error":{"code":"INVALID_REQUEST","message":"설명","retryable":false}}', "```", "",
        "| HTTP | 대표 code | 조건 |", "|---|---|---|", "| 400 | `INVALID_REQUEST`, `INVALID_EMAIL` | Bean Validation, 누락/형식 오류, JSON parse 오류 |", "| 401 | `UNAUTHENTICATED`, `INVALID_TOKEN`, `INVALID_CREDENTIALS` | token/credentials 오류 |", "| 403 | `EMAIL_VERIFICATION_REQUIRED` | 인증은 됐지만 이메일 인증 필요 |", "| 404 | `*_NOT_FOUND` | 소유 resource를 찾지 못함 |", "| 409 | `*_ALREADY_*`, `PLAN_NOT_ACTIVE`, `REVIEW_STALE` | 충돌/경합 |", "| 422 | `VALIDATION_ERROR`, `SENSITIVE_CHIP_REJECTED` | domain validation 오류 |", "| 500 | `INTERNAL_ERROR` | 예기치 않은 서버 오류 (legacy envelope: `error` string) |", "| 503 | `*_UNAVAILABLE`, `PLAN_CREATION_FAILED` | 외부 provider/plan engine 사용 불가 |", "",
        "## 3. Endpoint", "", f"생성 기준 public endpoint 수: **{len(endpoints)}**.", ""
    ]
    groups = defaultdict(list)
    for e in endpoints: groups[e["path"].split("/")[2] if len(e["path"].split("/")) > 2 else "root"].append(e)
    for group, entries in sorted(groups.items()):
        lines += [f"### {group}", "", "| Method | Path | Auth | 입력 | Success | Response | Handler |", "|---|---|---|---|---|---|---|"]
        for e in entries:
            inputs = ", ".join(f"{p['in']} `{p['name']}`: `{p['type']}`" for p in e["params"]) or "—"
            lines.append(f"| {e['method']} | `{e['path']}` | {e['auth']} | {inputs} | `{e['status']}` | `{e['response']}` | `{e['controller']}#{e['handler']}` |")
        lines.append("")
    lines += ["## 4. Request / response schema", "", "`—`는 source에 Bean Validation annotation이 없다는 뜻이며, optional을 의미하지는 않는다. nullability와 domain rule은 endpoint service의 추가 검증을 따른다.", ""]
    for name in sorted(dto_names):
        fields = dto_fields(name)
        if not fields: continue
        lines += [f"### `{name}`", "", "| Field | Java type | Validation |", "|---|---|---|"]
        for field, typ, rule in fields:
            values = enum_values(re.sub(r'<.*>', '', typ).split('.')[-1], name)
            type_text = f"`{typ}`" + (f" — enum: `{', '.join(values)}`" if values else "")
            lines.append(f"| `{field}` | {type_text} | {rule} |")
        lines.append("")
    lines += ["## 5. 유지보수", "", "- Controller mapping, DTO, validation, enum serialization 변경 시 generator를 실행하고 생성 diff를 같은 PR에 포함한다.", "- 이 문서는 runtime Swagger 대체물이 아니다. 현재 springdoc dependency가 없고 deployed Swagger endpoint는 auth boundary 뒤에 있으므로 controller/DTO source를 canonical contract로 사용한다.", "- Enum wire value는 `@JsonValue` 유무로 결정된다. `@JsonValue` enum은 lower-snake-case, 없는 enum은 `name()` 그대로(대문자)다. 표에 대문자 enum이 보이면 의도한 것인지 확인한다.",
        "- 같은 이름의 type이 여러 package에 있으면(`ActionType` 등) 참조한 DTO의 import로 해석한다. 새 동명 type을 추가할 때 이 해석이 맞는지 확인한다.",
        "- FE 정적 `ApiClient` literal route 대조는 FE `origin/dev` 기준 64개 호출 전부 method/path match, missing 0이었다. 이후 FE/BE 변경 시 같은 대조를 갱신한다.", ""]
    rendered = "\n".join(lines)
    if len(endpoints) < 70:
        raise SystemExit(f"unexpected endpoint count: {len(endpoints)}")
    if check:
        # 계약이 아니라 출처만 담는 줄이다. 이걸 비교에 넣으면 src/main/java를 건드린
        # 커밋마다 문서를 다시 만들어 커밋해야 통과하므로, 내용이 같아도 항상 stale이 된다.
        contract = lambda text: [l for l in text.splitlines() if not l.startswith("> **Source of truth:**")]
        if not OUT.exists() or contract(OUT.read_text(encoding="utf-8")) != contract(rendered):
            raise SystemExit("doc/API.md is stale; run python3 tools/generate_api_spec.py")
        print(f"verified {OUT.relative_to(ROOT)}: {len(endpoints)} endpoints, {len(dto_names)} referenced types")
        return
    OUT.write_text(rendered, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(endpoints)} endpoints, {len(dto_names)} referenced types")

if __name__ == "__main__": main()
