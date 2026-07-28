#!/usr/bin/env python3
"""Extract and exact-diff the review-owned W2 generic-policy contract v2."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence
from xml.etree import ElementTree

import yaml


JAVA_FILES = (
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyApplicationService.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyCommands.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyDraftService.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyEvaluationService.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyExceptions.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyFrozenPeriodProtection.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyLifecycleService.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyRecordSupport.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyRepository.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyTemplateService.java",
    "backend/src/main/java/com/szsemicon/hr/policy/application/PolicyValidationService.java",
    "backend/src/main/java/com/szsemicon/hr/policy/domain/PolicyModels.java",
    "backend/src/main/java/com/szsemicon/hr/policy/infrastructure/persistence/MyBatisPolicyRepository.java",
    "backend/src/main/java/com/szsemicon/hr/policy/infrastructure/persistence/PolicyMapper.java",
    "backend/src/main/java/com/szsemicon/hr/policy/infrastructure/persistence/PolicyRows.java",
    "backend/src/main/java/com/szsemicon/hr/policy/interfaces/rest/PolicyController.java",
    "backend/src/main/java/com/szsemicon/hr/policy/interfaces/rest/PolicyDtos.java",
    "backend/src/main/java/com/szsemicon/hr/policy/interfaces/rest/PolicyExceptionHandler.java",
)
MAPPER_XML = "backend/src/main/resources/mappers/PolicyMapper.xml"
H2_SCHEMA = "backend/src/test/resources/db/test-schema.sql"
OPENAPI = "api/openapi.yaml"
ACCEPTED_BASELINE_COMMIT = "32a6365bcebfa4cde1c523a64aac4c596b367e1b"
CHECKPOINT_GIT_COMMAND = (
    "git",
    "--git-dir=.umadev/checkpoints.git",
    "--work-tree=.",
)
V1_SUPERSESSION_DECISION = (
    "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w2-public-contract-v1-supersession-v2.json"
)
V2_FIXTURE = (
    "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w2-public-contract-v2.json"
)
V1_ARTIFACTS = (
    (
        "openspec/changes/wave3-attendance-setup-and-policies/specs/"
        "wave3-verification/oracles/w2-public-contract-v1.json",
        "72eb8502b0c467f5339792ac22fb48678105222cdef2fe5696136373108b5b50",
    ),
    (
        "openspec/changes/wave3-attendance-setup-and-policies/specs/"
        "wave3-verification/oracles/extract-w2-public-contract-v1.sh",
        "a319048ecfe5a496c933c278938b2f0119370e722cc62cba46c06ffbd1025b31",
    ),
    (
        "scripts/qa/verify_w2_public_contract.py",
        "8c8a9ce499d77dfce13996ac2c396a55416c5ca90bed3844d82dac82afa16538",
    ),
)
BASELINE_BLOBS = (
    (
        "backend/src/main/java/com/szsemicon/hr/policy/interfaces/rest/PolicyDtos.java",
        "b77d05d0c20bd49ecd56a20738bafd91325c59d9",
        "9b18732772017ad43e6d9028b9be2f0bd16748aca83a88fa366e80c63c2f5d7f",
    ),
    (
        "api/openapi.yaml",
        "8a96b8becce1254503a686d3053a5659814a7d70",
        "ed74dcb7c5a59eb98f23ddd043a99fbe2fd63842ed5d8935aff8fc4c69bc07ff",
    ),
    (
        "backend/src/main/java/com/szsemicon/hr/policy/domain/PolicyModels.java",
        "6e06e35b020d9e7a5228f575427d74b085e05274",
        "5b86da65da42d808382eb3335665fd248feaedd9cdc2d9830f3ae9f0a203b33d",
    ),
    (
        "backend/src/main/java/com/szsemicon/hr/policy/application/"
        "PolicyValidationService.java",
        "50b977ce54f9092d3a4a11491701115bac8981af",
        "0d68fb70fba9a3c416cabdf70eb3c961d387fbd463a3b28d47f421c897dce6e5",
    ),
    (
        "backend/src/main/resources/mappers/PolicyMapper.xml",
        "ab59bdc6b7ad80c7e27a10927624d19d80ec10a4",
        "b3af358354d49b2d869e1a039550303389bdb3b668afca0f5b1bd8ec9b50a9cb",
    ),
    (
        "backend/src/test/resources/db/test-schema.sql",
        "56210a3b8000e578ee0caf9563a9ec9b7b9a4576",
        "40a621e274cf73d78c26089ff3207ae5d62065fade85b1f89763d040d2052790",
    ),
    (
        "backend/src/main/resources/db/migration/"
        "V4__versioned_policy_foundation.sql",
        "8ad1b58eb21c1f8e487a56a2f99f13e43ac45f08",
        "22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8",
    ),
)
H2_TABLES = (
    "policy_template",
    "policy_version",
    "policy_scope_binding",
    "policy_publication_record",
    "policy_rollback_record",
    "audit_event",
)
FORBIDDEN_TOKENS = (
    "com.szsemicon.hr.attendance",
    "ATTENDANCE_MEAL_DEDUCTION",
    "ATTENDANCE_LATE_GRACE",
    "ATTENDANCE_SINGLE_MISSING_PUNCH",
    "validateAttendanceTemplate",
    "validateAttendanceParameters",
    "isAttendanceTemplate",
    "maximumLateMinutes",
    "groupChangeResets",
    "lockTemplate",
    "AttendanceLegalEntityId",
    "legalEntityId",
    "legal_entity_id",
)
CONTRACT_KEYS = {
    "format",
    "allowlist",
    "java",
    "mapper",
    "h2",
    "openapi",
    "boundary",
}
JAVA_TOKEN_PATTERN = re.compile(
    r"""
    "(?:\\.|[^"\\])*"
    |'(?:\\.|[^'\\])*'
    |[A-Za-z_$][A-Za-z0-9_$]*
    |\d+(?:\.\d+)?
    |\.\.\.
    |::|->|==|!=|<=|>=|&&|\|\||\+\+|--|<<|>>>|>>
    |[{}()\[\]<>.,;:@=?&|!+\-*/%]
    """,
    re.VERBOSE,
)
JAVA_MODIFIERS = {
    "public",
    "protected",
    "private",
    "abstract",
    "static",
    "final",
    "sealed",
    "non",
    "strictfp",
    "default",
    "synchronized",
    "native",
    "transient",
    "volatile",
}
TYPE_KINDS = {"class", "interface", "record", "enum"}
METHOD_EXCLUSIONS = {"if", "for", "while", "switch", "catch", "new", "return"}
SQL_TOKEN_PATTERN = re.compile(
    r"#\{[^}]+\}|\$\{[^}]+\}|'(?:''|[^'])*'|"
    r"<=|>=|<>|!=|[A-Za-z_][A-Za-z0-9_.$]*|\d+(?:\.\d+)?|[(),=*+\-/<>]"
)


class UniqueKeyLoader(yaml.SafeLoader):
    """YAML loader that fails instead of silently overwriting duplicate keys."""


def _construct_unique_mapping(
        loader: UniqueKeyLoader,
        node: yaml.nodes.MappingNode,
        deep: bool = False) -> dict[Any, Any]:
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ValueError(f"duplicate YAML key: {key}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)
for first_character, resolvers in copy.deepcopy(
        UniqueKeyLoader.yaml_implicit_resolvers).items():
    UniqueKeyLoader.yaml_implicit_resolvers[first_character] = [
        resolver
        for resolver in resolvers
        if resolver[0] != "tag:yaml.org,2002:timestamp"
    ]


def load_unique_yaml(source: str) -> Any:
    loader = UniqueKeyLoader(source)
    try:
        return loader.get_single_data()
    finally:
        loader.dispose()


def read_required(root: Path, relative_path: str) -> str:
    candidate = root / relative_path
    if not candidate.is_file():
        raise ValueError(f"required allowlist file is missing: {relative_path}")
    resolved = candidate.resolve()
    if root not in resolved.parents:
        raise ValueError(f"allowlist path escapes repository: {relative_path}")
    return candidate.read_text(encoding="utf-8")


def strip_java_comments(source: str) -> str:
    output: list[str] = []
    index = 0
    in_string: str | None = None
    while index < len(source):
        character = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if in_string is not None:
            output.append(character)
            if character == "\\" and index + 1 < len(source):
                output.append(source[index + 1])
                index += 2
                continue
            if character == in_string:
                in_string = None
            index += 1
            continue
        if character in {'"', "'"}:
            in_string = character
            output.append(character)
            index += 1
            continue
        if character == "/" and following == "/":
            while index < len(source) and source[index] != "\n":
                index += 1
            output.append("\n")
            index += 1
            continue
        if character == "/" and following == "*":
            index += 2
            while index + 1 < len(source) and source[index:index + 2] != "*/":
                output.append("\n" if source[index] == "\n" else " ")
                index += 1
            index += 2
            continue
        output.append(character)
        index += 1
    return "".join(output)


def java_tokens(source: str) -> list[str]:
    return JAVA_TOKEN_PATTERN.findall(strip_java_comments(source))


def canonical_tokens(tokens: Sequence[str]) -> str:
    if not tokens:
        return ""
    value = " ".join(tokens)
    value = re.sub(r"\s*([.<>\[\](),?])\s*", r"\1", value)
    value = re.sub(r"\s*::\s*", "::", value)
    value = re.sub(r"\s*\.\.\.\s*", "...", value)
    value = re.sub(r"\s+", " ", value).strip()
    return value


def visibility(tokens: Sequence[str]) -> str:
    for candidate in ("public", "protected", "private"):
        if candidate in tokens:
            return candidate
    return "package-private"


def find_matching(
        tokens: Sequence[str],
        start: int,
        opening: str,
        closing: str) -> int:
    if start >= len(tokens) or tokens[start] != opening:
        raise ValueError(f"expected {opening} at Java token {start}")
    depth = 0
    for index in range(start, len(tokens)):
        if tokens[index] == opening:
            depth += 1
        elif tokens[index] == closing:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError(f"unclosed Java token {opening} at {start}")


def split_top_level(
        tokens: Sequence[str],
        delimiter: str = ",") -> list[list[str]]:
    parts: list[list[str]] = []
    current: list[str] = []
    depths = {"(": 0, "[": 0, "<": 0, "{": 0}
    closing = {")": "(", "]": "[", ">": "<", "}": "{"}
    for token in tokens:
        if token in depths:
            depths[token] += 1
        elif token in closing and depths[closing[token]] > 0:
            depths[closing[token]] -= 1
        if token == delimiter and not any(depths.values()):
            parts.append(current)
            current = []
        else:
            current.append(token)
    if current:
        parts.append(current)
    return parts


def remove_annotations(tokens: Sequence[str]) -> tuple[list[str], list[dict[str, str]]]:
    clean: list[str] = []
    annotations: list[dict[str, str]] = []
    index = 0
    while index < len(tokens):
        if tokens[index] != "@":
            clean.append(tokens[index])
            index += 1
            continue
        start = index
        index += 1
        name_tokens: list[str] = []
        if index < len(tokens) and re.fullmatch(
                r"[A-Za-z_$][A-Za-z0-9_$]*", tokens[index]):
            name_tokens.append(tokens[index])
            index += 1
        while (
                index + 1 < len(tokens)
                and tokens[index] == "."
                and re.fullmatch(
                    r"[A-Za-z_$][A-Za-z0-9_$]*", tokens[index + 1])):
            name_tokens.extend(tokens[index:index + 2])
            index += 2
        arguments: list[str] = []
        if index < len(tokens) and tokens[index] == "(":
            end = find_matching(tokens, index, "(", ")")
            arguments = list(tokens[index + 1:end])
            index = end + 1
        annotations.append({
            "name": canonical_tokens(name_tokens),
            "arguments": canonical_tokens(arguments),
            "source": canonical_tokens(tokens[start:index]),
        })
    return clean, annotations


def parse_parameter(tokens: Sequence[str]) -> dict[str, Any]:
    clean, annotations = remove_annotations(tokens)
    clean = [token for token in clean if token not in {"final"}]
    identifiers = [
        index
        for index, token in enumerate(clean)
        if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", token)
    ]
    if not identifiers:
        raise ValueError(f"parameter lacks name: {canonical_tokens(tokens)}")
    name_index = identifiers[-1]
    parameter_name = clean[name_index]
    parameter_type = canonical_tokens(clean[:name_index] + clean[name_index + 1:])
    annotation_values = {
        annotation["name"]: annotation["arguments"].strip('"')
        for annotation in annotations
    }
    parameter = {
        "type": parameter_type,
        "name": parameter_name,
    }
    if annotation_values:
        parameter["annotations"] = annotation_values
    return parameter


def parse_parameters(tokens: Sequence[str]) -> list[dict[str, Any]]:
    if not tokens:
        return []
    return [
        parse_parameter(part)
        for part in split_top_level(tokens)
        if part
    ]


def member_modifiers(tokens: Sequence[str]) -> list[str]:
    return [token for token in tokens if token in JAVA_MODIFIERS]


def strip_member_prefix(tokens: Sequence[str]) -> tuple[list[str], list[dict[str, str]]]:
    clean, annotations = remove_annotations(tokens)
    while clean and clean[0] in JAVA_MODIFIERS:
        clean.pop(0)
    return clean, annotations


def compact_annotations(
        annotations: Sequence[dict[str, str]]) -> dict[str, str]:
    return {
        annotation["name"]: annotation["arguments"]
        for annotation in annotations
    }


def find_method_parentheses(header: Sequence[str]) -> tuple[int, int] | None:
    pairs: list[tuple[int, int]] = []
    index = 0
    while index < len(header):
        if header[index] == "(":
            end = find_matching(header, index, "(", ")")
            if index > 0 and re.fullmatch(
                    r"[A-Za-z_$][A-Za-z0-9_$]*", header[index - 1]):
                pairs.append((index, end))
            index = end + 1
        else:
            index += 1
    return pairs[-1] if pairs else None


def parse_callable(
        header: Sequence[str],
        type_name: str,
        parent_kind: str) -> tuple[str, dict[str, Any]] | None:
    pair = find_method_parentheses(header)
    if pair is None:
        clean, annotations = strip_member_prefix(header)
        if clean == [type_name]:
            declared = visibility(header)
            effective = "public" if parent_kind in {"record", "interface"} else declared
            return "constructor", {
                "name": type_name,
                "declaredVisibility": declared,
                "effectiveVisibility": effective,
                "parameters": [],
                "annotations": compact_annotations(annotations),
                "compact": True,
            }
        return None
    opening, closing = pair
    name = header[opening - 1]
    if name in METHOD_EXCLUSIONS or "=" in header[:opening]:
        return None
    prefix = list(header[:opening - 1])
    clean_prefix, annotations = strip_member_prefix(prefix)
    declared = visibility(prefix)
    effective = "public" if parent_kind == "interface" and declared == "package-private" else declared
    suffix = list(header[closing + 1:])
    throws: list[str] = []
    if "throws" in suffix:
        throws_index = suffix.index("throws")
        throws = [
            canonical_tokens(part)
            for part in split_top_level(suffix[throws_index + 1:])
        ]
    parameters = parse_parameters(header[opening + 1:closing])
    if name == type_name and not clean_prefix:
        constructor = {
            "name": name,
            "declaredVisibility": declared,
            "effectiveVisibility": effective,
            "parameters": parameters,
            "annotations": compact_annotations(annotations),
            "compact": False,
        }
        if throws:
            constructor["throws"] = throws
        return "constructor", constructor
    if not clean_prefix:
        return None
    method = {
        "returnType": canonical_tokens(clean_prefix),
        "name": name,
        "declaredVisibility": declared,
        "effectiveVisibility": effective,
        "parameters": parameters,
        "annotations": compact_annotations(annotations),
    }
    if throws:
        method["throws"] = throws
    return "method", method


def scan_member_delimiter(tokens: Sequence[str], start: int, end: int) -> tuple[int, str]:
    parentheses = 0
    brackets = 0
    index = start
    while index < end:
        token = tokens[index]
        if token == "(":
            parentheses += 1
        elif token == ")":
            parentheses -= 1
        elif token == "[":
            brackets += 1
        elif token == "]":
            brackets -= 1
        elif token in {";", "{"} and parentheses == 0 and brackets == 0:
            return index, token
        index += 1
    return end, ""


def parse_enum_constants(tokens: Sequence[str], start: int, end: int) -> tuple[list[str], int]:
    constants_end = end
    parentheses = 0
    braces = 0
    for index in range(start, end):
        token = tokens[index]
        if token == "(":
            parentheses += 1
        elif token == ")":
            parentheses -= 1
        elif token == "{":
            braces += 1
        elif token == "}":
            braces -= 1
        elif token == ";" and parentheses == 0 and braces == 0:
            constants_end = index
            break
    constants: list[str] = []
    for part in split_top_level(tokens[start:constants_end]):
        clean, _ = remove_annotations(part)
        identifiers = [
            token
            for token in clean
            if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", token)
        ]
        if identifiers:
            constants.append(identifiers[0])
    return constants, min(constants_end + 1, end)


def find_type_declaration(
        header: Sequence[str]) -> tuple[int, str] | None:
    for index, token in enumerate(header):
        if (
                token in TYPE_KINDS
                and (index == 0 or header[index - 1] != ".")
                and index + 1 < len(header)
                and re.fullmatch(
                    r"[A-Za-z_$][A-Za-z0-9_$]*", header[index + 1])):
            return index, token
    return None


def parse_type(
        tokens: Sequence[str],
        declaration_start: int,
        kind_index: int,
        parent_name: str | None) -> tuple[dict[str, Any], int]:
    kind = tokens[kind_index]
    name = tokens[kind_index + 1]
    header_end = kind_index + 2
    record_components: list[dict[str, Any]] = []
    if kind == "record":
        while header_end < len(tokens) and tokens[header_end] != "(":
            header_end += 1
        record_end = find_matching(tokens, header_end, "(", ")")
        record_components = parse_parameters(tokens[header_end + 1:record_end])
        header_end = record_end + 1
    body_start = header_end
    while body_start < len(tokens) and tokens[body_start] != "{":
        body_start += 1
    if body_start >= len(tokens):
        raise ValueError(f"type {name} has no body")
    body_end = find_matching(tokens, body_start, "{", "}")
    declaration_prefix = tokens[declaration_start:kind_index]
    type_entry: dict[str, Any] = {
        "name": name,
        "qualifiedName": f"{parent_name}.{name}" if parent_name else name,
        "kind": kind,
        "visibility": visibility(declaration_prefix),
        "modifiers": member_modifiers(declaration_prefix),
        "recordComponents": record_components,
        "enumConstants": [],
        "constructors": [],
        "methods": [],
    }
    index = body_start + 1
    if kind == "enum":
        type_entry["enumConstants"], index = parse_enum_constants(
            tokens, index, body_end)
    while index < body_end:
        while index < body_end and tokens[index] == ";":
            index += 1
        if index >= body_end:
            break
        delimiter_index, delimiter = scan_member_delimiter(tokens, index, body_end)
        if not delimiter:
            break
        header = tokens[index:delimiter_index]
        nested = find_type_declaration(header)
        if delimiter == "{" and nested is not None:
            nested_kind_index = index + nested[0]
            child, nested_end = parse_type(
                tokens,
                index,
                nested_kind_index,
                type_entry["qualifiedName"],
            )
            type_entry.setdefault("nestedTypes", []).append(child)
            index = nested_end + 1
            continue
        callable_entry = parse_callable(header, name, kind)
        if callable_entry is not None:
            callable_kind, value = callable_entry
            if value["effectiveVisibility"] in {"public", "package-private"}:
                type_entry[
                    "constructors" if callable_kind == "constructor" else "methods"
                ].append(value)
        if delimiter == "{":
            index = find_matching(tokens, delimiter_index, "{", "}") + 1
        else:
            index = delimiter_index + 1
    type_entry.setdefault("nestedTypes", [])
    return type_entry, body_end


def parse_java(source: str) -> dict[str, Any]:
    tokens = java_tokens(source)
    package_name = ""
    if "package" in tokens:
        package_index = tokens.index("package")
        package_end = tokens.index(";", package_index)
        package_name = canonical_tokens(tokens[package_index + 1:package_end])
    types: list[dict[str, Any]] = []
    brace_depth = 0
    index = 0
    declaration_start = 0
    while index < len(tokens):
        token = tokens[index]
        if token == "{":
            brace_depth += 1
        elif token == "}":
            brace_depth -= 1
        elif token == ";":
            declaration_start = index + 1
        elif brace_depth == 0 and token in TYPE_KINDS:
            parsed, type_end = parse_type(
                tokens, declaration_start, index, None)
            types.append(parsed)
            index = type_end
            declaration_start = index + 1
        index += 1
    if not types:
        raise ValueError("Java source contains no top-level type")
    return {"package": package_name, "types": types}


def xml_tree(element: ElementTree.Element) -> dict[str, Any]:
    children = [xml_tree(child) for child in list(element)]
    return {
        "tag": element.tag,
        "attributes": dict(sorted(element.attrib.items())),
        "text": " ".join((element.text or "").split()),
        "children": children,
        "tails": [" ".join((child.tail or "").split()) for child in list(element)],
    }


def expanded_xml_tokens(
        element: ElementTree.Element,
        fragments: dict[str, ElementTree.Element],
        include_stack: tuple[str, ...] = ()) -> list[str]:
    tokens: list[str] = []
    if element.text:
        tokens.extend(SQL_TOKEN_PATTERN.findall(element.text))
    for child in list(element):
        if child.tag == "include":
            reference = child.attrib["refid"]
            if reference in include_stack:
                raise ValueError(f"recursive Mapper include: {reference}")
            fragment = fragments.get(reference)
            if fragment is None:
                raise ValueError(f"unknown Mapper include: {reference}")
            tokens.append(f"<include:{reference}>")
            tokens.extend(expanded_xml_tokens(
                fragment, fragments, include_stack + (reference,)))
            tokens.append(f"</include:{reference}>")
        else:
            attributes = ",".join(
                f"{name}={value}"
                for name, value in sorted(child.attrib.items())
            )
            tokens.append(f"<{child.tag}:{attributes}>")
            tokens.extend(expanded_xml_tokens(child, fragments, include_stack))
            tokens.append(f"</{child.tag}>")
        if child.tail:
            tokens.extend(SQL_TOKEN_PATTERN.findall(child.tail))
    return tokens


def flatten_types(types: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    flattened: list[dict[str, Any]] = []
    for type_entry in types:
        flattened.append(type_entry)
        flattened.extend(flatten_types(type_entry["nestedTypes"]))
    return flattened


def parse_mapper(xml_source: str, mapper_java: dict[str, Any]) -> dict[str, Any]:
    root = ElementTree.fromstring(xml_source)
    if root.tag != "mapper":
        raise ValueError("PolicyMapper.xml root must be mapper")
    fragments = {
        element.attrib["id"]: element
        for element in root
        if element.tag == "sql"
    }
    elements: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    supported_tags = {"sql", "resultMap", "select", "insert", "update", "delete"}
    for element in root:
        if element.tag not in supported_tags:
            continue
        identifier = element.attrib.get("id")
        if not identifier:
            raise ValueError(f"Mapper {element.tag} lacks id")
        if identifier in seen_ids:
            raise ValueError(f"duplicate Mapper id: {identifier}")
        seen_ids.add(identifier)
        entry = {
            "tag": element.tag,
            "id": identifier,
            "attributes": dict(sorted(element.attrib.items())),
        }
        if element.tag == "resultMap":
            entry["tree"] = xml_tree(element)
        if element.tag in {"sql", "select", "insert", "update", "delete"}:
            entry["sqlTokens"] = expanded_xml_tokens(element, fragments)
        elements.append(entry)
    mapper_type = next(
        type_entry
        for type_entry in flatten_types(mapper_java["types"])
        if type_entry["name"] == "PolicyMapper"
    )
    java_method_names = [method["name"] for method in mapper_type["methods"]]
    xml_statement_ids = [
        element["id"]
        for element in elements
        if element["tag"] in {"select", "insert", "update", "delete"}
    ]
    return {
        "namespace": root.attrib.get("namespace"),
        "elements": elements,
        "statementClosure": {
            "javaMethodNames": java_method_names,
            "xmlStatementIds": xml_statement_ids,
            "missingInJava": sorted(set(xml_statement_ids) - set(java_method_names)),
            "missingInXml": sorted(set(java_method_names) - set(xml_statement_ids)),
        },
    }


def strip_sql_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    return re.sub(r"--[^\n]*", " ", source)


def matching_character(source: str, start: int, opening: str, closing: str) -> int:
    if source[start] != opening:
        raise ValueError(f"expected {opening} at SQL character {start}")
    depth = 0
    in_string = False
    index = start
    while index < len(source):
        character = source[index]
        if character == "'":
            if in_string and index + 1 < len(source) and source[index + 1] == "'":
                index += 2
                continue
            in_string = not in_string
        elif not in_string:
            if character == opening:
                depth += 1
            elif character == closing:
                depth -= 1
                if depth == 0:
                    return index
        index += 1
    raise ValueError(f"unclosed SQL {opening}")


def split_sql_items(body: str) -> list[str]:
    items: list[str] = []
    start = 0
    depth = 0
    in_string = False
    index = 0
    while index < len(body):
        character = body[index]
        if character == "'":
            if in_string and index + 1 < len(body) and body[index + 1] == "'":
                index += 2
                continue
            in_string = not in_string
        elif not in_string:
            if character == "(":
                depth += 1
            elif character == ")":
                depth -= 1
            elif character == "," and depth == 0:
                items.append(body[start:index].strip())
                start = index + 1
        index += 1
    trailing = body[start:].strip()
    if trailing:
        items.append(trailing)
    return items


def normalize_sql_fragment(value: str) -> str:
    return " ".join(SQL_TOKEN_PATTERN.findall(value))


def parse_column(definition: str) -> dict[str, Any]:
    match = re.match(
        r"(?is)^([A-Za-z_][A-Za-z0-9_]*)\s+"
        r"([A-Za-z]+)(?:\s*\(([^)]*)\))?(.*)$",
        definition,
    )
    if not match:
        raise ValueError(f"unparseable H2 column: {definition}")
    name, base_type, arguments, remainder = match.groups()
    type_arguments = [
        int(argument.strip())
        for argument in arguments.split(",")
    ] if arguments else []
    default_match = re.search(
        r"(?is)\bDEFAULT\s+(.+?)(?=\s+(?:NOT\s+NULL|NULL|PRIMARY\s+KEY|"
        r"UNIQUE|CHECK|REFERENCES)\b|$)",
        remainder,
    )
    primary_key = bool(re.search(r"(?i)\bPRIMARY\s+KEY\b", remainder))
    return {
        "name": name.lower(),
        "logicalType": base_type.upper(),
        "length": type_arguments[0] if len(type_arguments) == 1 else None,
        "precision": type_arguments[0] if len(type_arguments) == 2 else None,
        "scale": type_arguments[1] if len(type_arguments) == 2 else None,
        "nullable": not (
            primary_key or bool(re.search(r"(?i)\bNOT\s+NULL\b", remainder))
        ),
        "default": (
            normalize_sql_fragment(default_match.group(1))
            if default_match else None
        ),
        "primaryKey": primary_key,
        "unique": bool(re.search(r"(?i)\bUNIQUE\b", remainder)),
    }


def parse_constraint(definition: str) -> dict[str, Any]:
    normalized = " ".join(definition.split())
    name: str | None = None
    body = normalized
    named = re.match(
        r"(?is)^CONSTRAINT\s+([A-Za-z_][A-Za-z0-9_]*)\s+(.+)$",
        normalized,
    )
    if named:
        name = named.group(1)
        body = named.group(2)
    foreign_key = re.match(
        r"(?is)^FOREIGN\s+KEY\s*\(([^)]*)\)\s+REFERENCES\s+"
        r"([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)$",
        body,
    )
    if foreign_key:
        return {
            "type": "FOREIGN_KEY",
            "name": name,
            "columns": [value.strip().lower() for value in foreign_key.group(1).split(",")],
            "referencedTable": foreign_key.group(2).lower(),
            "referencedColumns": [
                value.strip().lower()
                for value in foreign_key.group(3).split(",")
            ],
        }
    check = re.match(r"(?is)^CHECK\s*\((.*)\)$", body)
    if check:
        return {
            "type": "CHECK",
            "name": name,
            "expression": normalize_sql_fragment(check.group(1)),
        }
    key = re.match(r"(?is)^(PRIMARY\s+KEY|UNIQUE)\s*\(([^)]*)\)$", body)
    if key:
        return {
            "type": key.group(1).upper().replace(" ", "_"),
            "name": name,
            "columns": [value.strip().lower() for value in key.group(2).split(",")],
        }
    raise ValueError(f"unparseable H2 constraint: {definition}")


def extract_table_block(source: str, table_name: str) -> tuple[str, str]:
    matches = list(re.finditer(
        rf"(?is)\bCREATE\s+TABLE\s+{re.escape(table_name)}\s*\(",
        source,
    ))
    if len(matches) != 1:
        raise ValueError(
            f"expected exactly one CREATE TABLE for {table_name}, found {len(matches)}")
    opening = source.find("(", matches[0].start())
    closing = matching_character(source, opening, "(", ")")
    semicolon = source.find(";", closing)
    if semicolon < 0:
        raise ValueError(f"CREATE TABLE {table_name} lacks semicolon")
    return source[opening + 1:closing], source[matches[0].start():semicolon + 1]


def parse_h2(source: str) -> tuple[dict[str, Any], str]:
    clean = strip_sql_comments(source)
    tables: dict[str, Any] = {}
    boundary_fragments: list[str] = []
    for table_name in H2_TABLES:
        body, raw = extract_table_block(clean, table_name)
        boundary_fragments.append(raw)
        columns: list[dict[str, Any]] = []
        constraints: list[dict[str, Any]] = []
        for item in split_sql_items(body):
            if re.match(
                    r"(?is)^(?:CONSTRAINT\b|UNIQUE\b|PRIMARY\s+KEY\b|CHECK\b|"
                    r"FOREIGN\s+KEY\b)",
                    item):
                constraints.append(parse_constraint(item))
            else:
                column = parse_column(item)
                columns.append(column)
                if column["primaryKey"]:
                    constraints.append({
                        "type": "PRIMARY_KEY",
                        "name": None,
                        "columns": [column["name"]],
                    })
                if column["unique"]:
                    constraints.append({
                        "type": "UNIQUE",
                        "name": None,
                        "columns": [column["name"]],
                    })
        tables[table_name] = {
            "columns": columns,
            "constraints": constraints,
        }
    indexes: list[dict[str, Any]] = []
    index_pattern = re.compile(
        r"(?is)\bCREATE\s+(UNIQUE\s+)?INDEX\s+"
        r"([A-Za-z_][A-Za-z0-9_]*)\s+ON\s+"
        r"([A-Za-z_][A-Za-z0-9_]*)\s*\("
    )
    seen_indexes: set[str] = set()
    for match in index_pattern.finditer(clean):
        table_name = match.group(3).lower()
        if table_name not in H2_TABLES:
            continue
        opening = clean.find("(", match.start())
        closing = matching_character(clean, opening, "(", ")")
        semicolon = clean.find(";", closing)
        if semicolon < 0:
            raise ValueError(f"index {match.group(2)} lacks semicolon")
        name = match.group(2)
        if name in seen_indexes:
            raise ValueError(f"duplicate H2 index: {name}")
        seen_indexes.add(name)
        column_expression = clean[opening + 1:closing]
        indexes.append({
            "name": name,
            "unique": match.group(1) is not None,
            "table": table_name,
            "columns": [
                normalize_sql_fragment(item)
                for item in split_sql_items(column_expression)
            ],
        })
        boundary_fragments.append(clean[match.start():semicolon + 1])
    return {"tables": tables, "indexes": indexes}, "\n".join(boundary_fragments)


def normalize_yaml(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): normalize_yaml(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [normalize_yaml(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def json_pointer_get(document: dict[str, Any], reference: str) -> Any:
    if not reference.startswith("#/"):
        raise ValueError(f"external OpenAPI reference is forbidden: {reference}")
    current: Any = document
    for encoded in reference[2:].split("/"):
        key = encoded.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or key not in current:
            raise ValueError(f"dangling OpenAPI reference: {reference}")
        current = current[key]
    return current


def resolve_parameter(
        document: dict[str, Any],
        parameter: dict[str, Any]) -> dict[str, Any]:
    source_reference = parameter.get("$ref")
    resolved = (
        json_pointer_get(document, source_reference)
        if source_reference else parameter
    )
    if not isinstance(resolved, dict):
        raise ValueError("OpenAPI parameter must be an object")
    exported = normalize_yaml(resolved)
    if source_reference:
        exported = {"sourceRef": source_reference, **exported}
    return exported


def collect_openapi_closure(
        document: dict[str, Any],
        roots: Iterable[Any]) -> dict[str, dict[str, Any]]:
    collected: dict[str, dict[str, Any]] = {
        "schemas": {},
        "parameters": {},
        "responses": {},
        "headers": {},
    }
    pending = list(roots)
    visited: set[str] = set()
    while pending:
        value = pending.pop()
        if isinstance(value, dict):
            reference = value.get("$ref")
            if isinstance(reference, str) and reference.startswith("#/components/"):
                if reference not in visited:
                    visited.add(reference)
                    target = json_pointer_get(document, reference)
                    segments = reference.split("/")
                    if len(segments) != 4 or segments[2] not in collected:
                        raise ValueError(
                            f"unsupported W2 OpenAPI component reference: {reference}")
                    collected[segments[2]][segments[3]] = normalize_yaml(target)
                    pending.append(target)
            pending.extend(value.values())
        elif isinstance(value, list):
            pending.extend(value)
    return collected


def parse_openapi(source: str) -> tuple[dict[str, Any], str]:
    document = load_unique_yaml(source)
    if not isinstance(document, dict):
        raise ValueError("OpenAPI root must be an object")
    paths = document.get("paths")
    if not isinstance(paths, dict):
        raise ValueError("OpenAPI paths must be an object")
    selected_paths = {
        path: path_item
        for path, path_item in paths.items()
        if path == "/policy-templates" or path.startswith("/policy-templates/")
    }
    methods = {"get", "post", "put", "patch", "delete", "options", "head", "trace"}
    operations: list[dict[str, Any]] = []
    closure_roots: list[Any] = []
    seen_operations: set[tuple[str, str]] = set()
    for path, path_item in selected_paths.items():
        if not isinstance(path_item, dict):
            raise ValueError(f"OpenAPI path item must be object: {path}")
        path_parameters = path_item.get("parameters", [])
        for method, operation in path_item.items():
            if method.lower() not in methods:
                continue
            if not isinstance(operation, dict):
                raise ValueError(f"OpenAPI operation must be object: {method} {path}")
            identity = (method.lower(), path)
            if identity in seen_operations:
                raise ValueError(f"duplicate OpenAPI operation: {identity}")
            seen_operations.add(identity)
            raw_parameters = list(path_parameters) + list(operation.get("parameters", []))
            resolved_parameters = [
                resolve_parameter(document, parameter)
                for parameter in raw_parameters
            ]
            request_body = normalize_yaml(operation.get("requestBody"))
            responses = normalize_yaml(operation.get("responses", {}))
            operations.append({
                "method": method.lower(),
                "path": path,
                "operationId": operation.get("operationId"),
                "xCapability": operation.get("x-capability"),
                "parameters": resolved_parameters,
                "requestBody": request_body,
                "responses": responses,
            })
            closure_roots.extend(raw_parameters)
            if "requestBody" in operation:
                closure_roots.append(operation["requestBody"])
            closure_roots.append(operation.get("responses", {}))
    operations.sort(key=lambda operation: (operation["path"], operation["method"]))
    closure = collect_openapi_closure(document, closure_roots)
    boundary = json.dumps(
        {"paths": normalize_yaml(selected_paths), "components": closure},
        ensure_ascii=False,
        sort_keys=True,
    )
    return {"operations": operations, "components": closure}, boundary


def boundary_violations(surfaces: dict[str, str]) -> list[dict[str, Any]]:
    violations: list[dict[str, Any]] = []
    for token in FORBIDDEN_TOKENS:
        matched_surfaces = [
            name
            for name, source in surfaces.items()
            if token in source
        ]
        if matched_surfaces:
            violations.append({
                "token": token,
                "surfaces": sorted(matched_surfaces),
            })
    return violations


def extract_contract(repo_root: Path) -> dict[str, Any]:
    root = repo_root.resolve()
    if not root.is_dir():
        raise ValueError(f"repository root is not a directory: {root}")
    return extract_contract_from_reader(
        lambda relative_path: read_required(root, relative_path))


def extract_contract_from_reader(
        read_source: Callable[[str], str]) -> dict[str, Any]:
    java: dict[str, Any] = {}
    boundary_sources: dict[str, str] = {}
    for relative_path in JAVA_FILES:
        if "/attendance/" in relative_path:
            raise ValueError("attendance source entered W2 Java allowlist")
        source = read_source(relative_path)
        java[relative_path] = parse_java(source)
        boundary_sources[f"java:{relative_path}"] = source
    mapper_source = read_source(MAPPER_XML)
    mapper = parse_mapper(mapper_source, java[
        "backend/src/main/java/com/szsemicon/hr/policy/infrastructure/persistence/"
        "PolicyMapper.java"
    ])
    boundary_sources[f"mapper:{MAPPER_XML}"] = mapper_source
    h2, h2_boundary = parse_h2(read_source(H2_SCHEMA))
    boundary_sources[f"h2:{H2_SCHEMA}"] = h2_boundary
    openapi, openapi_boundary = parse_openapi(read_source(OPENAPI))
    boundary_sources[f"openapi:{OPENAPI}"] = openapi_boundary
    return {
        "format": "w2-public-contract-structured-export-v2",
        "allowlist": {
            "java": list(JAVA_FILES),
            "mapperXml": MAPPER_XML,
            "h2Schema": H2_SCHEMA,
            "h2Tables": list(H2_TABLES),
            "openapi": OPENAPI,
            "openapiPathPrefix": "/policy-templates",
        },
        "java": java,
        "mapper": mapper,
        "h2": h2,
        "openapi": openapi,
        "boundary": {
            "forbiddenTokens": list(FORBIDDEN_TOKENS),
            "violations": boundary_violations(boundary_sources),
        },
    }


def write_contract(contract: dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(
            contract,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )


def diff_values(
        expected: Any,
        actual: Any,
        path: str = "$",
        limit: int = 80) -> list[str]:
    differences: list[str] = []

    def visit(expected_value: Any, actual_value: Any, pointer: str) -> None:
        if len(differences) >= limit:
            return
        if type(expected_value) is not type(actual_value):
            differences.append(
                f"{pointer}: type {type(actual_value).__name__} != "
                f"{type(expected_value).__name__}")
            return
        if isinstance(expected_value, dict):
            expected_keys = set(expected_value)
            actual_keys = set(actual_value)
            for key in sorted(expected_keys - actual_keys):
                differences.append(f"{pointer}/{key}: missing")
            for key in sorted(actual_keys - expected_keys):
                differences.append(f"{pointer}/{key}: unexpected")
            for key in sorted(expected_keys & actual_keys):
                visit(expected_value[key], actual_value[key], f"{pointer}/{key}")
        elif isinstance(expected_value, list):
            if len(expected_value) != len(actual_value):
                differences.append(
                    f"{pointer}: length {len(actual_value)} != {len(expected_value)}")
            for index, (expected_item, actual_item) in enumerate(
                    zip(expected_value, actual_value)):
                visit(expected_item, actual_item, f"{pointer}/{index}")
        elif expected_value != actual_value:
            differences.append(
                f"{pointer}: {actual_value!r} != {expected_value!r}")

    visit(expected, actual, path)
    return differences


def validate_contract_shape(contract: dict[str, Any]) -> None:
    if set(contract) != CONTRACT_KEYS:
        raise AssertionError(
            f"unknown/missing v2 contract keys: {sorted(set(contract) ^ CONTRACT_KEYS)}")
    if contract["format"] != "w2-public-contract-structured-export-v2":
        raise AssertionError("unexpected v2 export format")
    if contract["allowlist"]["java"] != list(JAVA_FILES):
        raise AssertionError("W2 Java allowlist differs or is reordered")
    if len(contract["allowlist"]["java"]) != len(
            set(contract["allowlist"]["java"])):
        raise AssertionError("duplicate W2 Java allowlist entry")
    if set(contract["java"]) != set(JAVA_FILES):
        raise AssertionError("W2 Java export is missing or has extra files")
    if any("/attendance/" in path for path in contract["java"]):
        raise AssertionError("attendance Java entered W2 export")
    closure = contract["mapper"]["statementClosure"]
    if closure["missingInJava"] or closure["missingInXml"]:
        raise AssertionError(
            "PolicyMapper Java/XML statement closure differs: "
            f"{json.dumps(closure, ensure_ascii=False, sort_keys=True)}")
    element_ids = [entry["id"] for entry in contract["mapper"]["elements"]]
    if len(element_ids) != len(set(element_ids)):
        raise AssertionError("duplicate PolicyMapper XML id")
    if set(contract["h2"]["tables"]) != set(H2_TABLES):
        raise AssertionError("W2 H2 table closure differs")
    operation_keys = [
        (operation["method"], operation["path"])
        for operation in contract["openapi"]["operations"]
    ]
    if len(operation_keys) != len(set(operation_keys)):
        raise AssertionError("duplicate W2 OpenAPI operation")


def expected_supersession_decision() -> dict[str, Any]:
    return {
        "authoritativeAcceptedBaseline": ACCEPTED_BASELINE_COMMIT,
        "decision": "INVALIDATED_SUPERSEDED",
        "frozenV1Artifacts": [
            {"path": path, "sha256": sha256}
            for path, sha256 in V1_ARTIFACTS
        ],
        "id": "w2-public-contract-v1-supersession-v2",
        "invalidation": {
            "capturedPollution": [
                "public MyBatisPolicyRepository.lockTemplate",
                "PolicyMapper.lockTemplate",
                "PolicyMapper.xml lockTemplate",
            ],
            "reason": (
                "The v1 expected contract captured an unaccepted W3 "
                "lockTemplate chain in the W2 generic-policy surface."
            ),
            "reasonCode": "CAPTURED_UNACCEPTED_W3_LOCK_TEMPLATE_POLLUTION",
        },
        "rules": {
            "currentSourceMustNotBeChangedToMatchV1": True,
            "v1CurrentSourcePositiveGateProhibited": True,
            "v1FilesMustRemainByteIdentical": True,
            "v1IntegrityCheckRequired": True,
            "v2CurrentSourceExactPositiveGateRequired": True,
        },
        "status": "INVALIDATED_SUPERSEDED",
        "supersededBy": V2_FIXTURE,
        "version": 2,
        "v2ExpectedConstruction": {
            "method": (
                "manual pre-anchoring against the immutable accepted baseline "
                "and immutable V4/v1 evidence"
            ),
            "mustNotUseCurrentActual": True,
            "mustNotUseTestedExtractor": True,
        },
    }


def expected_fixture_metadata() -> dict[str, Any]:
    return {
        "acceptedBaselineCommit": ACCEPTED_BASELINE_COMMIT,
        "additiveCompatibilityDecisions": [
            {
                "decision": "AUTHORIZED_RETAINED",
                "id": "W2-ADD-SCOPE-ATTENDANCE-GROUP",
                "rationale": (
                    "ATTENDANCE_GROUP is the sole authorized additive "
                    "compatibility enum value on the W2 Java/OpenAPI scope "
                    "surface; it does not authorize lockTemplate, attendance "
                    "legal-entity fields, or generic attendance validation."
                ),
                "surfaces": [
                    "backend/src/main/java/com/szsemicon/hr/policy/domain/"
                    "PolicyModels.java#PolicyModels.ScopeType",
                    "api/openapi.yaml#/components/schemas/ScopeType",
                ],
                "value": "ATTENDANCE_GROUP",
            }
        ],
        "candidateStatus": "AWAITING_INDEPENDENT_REVIEW",
        "construction": {
            "expectedDerivedFromCurrentActual": False,
            "expectedGeneratedByTestedExtractor": False,
            "method": (
                "manual pre-anchoring by itemized comparison with the immutable "
                "accepted baseline and immutable V4/v1 evidence"
            ),
        },
        "fixtureVersion": 2,
        "provenance": {
            "acceptedBaselineBlobs": [
                {
                    "gitBlobSha1": git_blob_sha1,
                    "path": path,
                    "sha256": sha256,
                }
                for path, git_blob_sha1, sha256 in BASELINE_BLOBS
            ],
            "baselineAdjustments": [
                {
                    "allowedDifference": {
                        "constraintNameOnly": True,
                    },
                    "expression": (
                        "effective_to IS NULL OR "
                        "effective_to >= effective_from"
                    ),
                    "id": "V4-PERIOD-CHECK-H2-SYNONYMOUS-MIRROR",
                    "kind": "SYNONYMOUS_CONSTRAINT_MIRROR",
                    "source": {
                        "constraintName": "ck_policy_version_period",
                        "path": BASELINE_BLOBS[6][0],
                        "table": "policy_version",
                    },
                    "target": {
                        "constraintName": "ck_test_policy_version_period",
                        "path": H2_SCHEMA,
                        "table": "policy_version",
                    },
                }
            ],
            "baselineDrift": {
                "java": [],
                "openapi": [],
            },
            "immutableEvidence": [
                {
                    "path": V1_ARTIFACTS[0][0],
                    "scopeUsed": (
                        "V4/H2 critical constraint and additive-compatibility "
                        "cross-check only; superseded lockTemplate expectations "
                        "are rejected"
                    ),
                    "sha256": V1_ARTIFACTS[0][1],
                }
            ],
            "reviewerNote": (
                "This is a candidate for independent review, not evidence that "
                "independent review has passed."
            ),
        },
        "v1SupersessionDecision": V1_SUPERSESSION_DECISION,
    }


def validate_fixture_document(document: dict[str, Any]) -> dict[str, Any]:
    expected_keys = set(expected_fixture_metadata()) | {"contract"}
    if set(document) != expected_keys:
        raise AssertionError(
            "v2 fixture has unknown or missing top-level structure: "
            f"{sorted(set(document) ^ expected_keys)}")
    metadata = {key: value for key, value in document.items() if key != "contract"}
    differences = diff_values(expected_fixture_metadata(), metadata, "$.metadata")
    if differences:
        raise AssertionError(
            "v2 fixture metadata differs from the fixed candidate provenance:\n"
            + "\n".join(differences))
    return document["contract"]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_checkpoint_git(repo_root: Path, *arguments: str) -> bytes:
    result = subprocess.run(
        [*CHECKPOINT_GIT_COMMAND, *arguments],
        cwd=repo_root,
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise AssertionError(
            "checkpoint Git read failed: "
            f"{' '.join((*CHECKPOINT_GIT_COMMAND, *arguments))}: "
            f"exit={result.returncode}: {detail}")
    return result.stdout


def checkpoint_blob(
        repo_root: Path,
        commit: str,
        relative_path: str) -> tuple[str, bytes]:
    tree_line = run_checkpoint_git(
        repo_root,
        "ls-tree",
        "--full-tree",
        commit,
        "--",
        relative_path,
    )
    lines = tree_line.splitlines()
    if len(lines) != 1 or b"\t" not in lines[0]:
        raise AssertionError(
            f"checkpoint path does not resolve to one blob: {commit}:{relative_path}")
    metadata, encoded_path = lines[0].split(b"\t", 1)
    fields = metadata.split()
    if (
            len(fields) != 3
            or fields[1] != b"blob"
            or encoded_path.decode("utf-8") != relative_path):
        raise AssertionError(
            f"checkpoint path is not the required blob: {commit}:{relative_path}")
    object_id = fields[2].decode("ascii")
    content = run_checkpoint_git(repo_root, "cat-file", "blob", object_id)
    return object_id, content


def named_check(
        source: str,
        table_name: str,
        constraint_name: str) -> dict[str, Any]:
    body, _ = extract_table_block(strip_sql_comments(source), table_name)
    matches = [
        parsed
        for item in split_sql_items(body)
        if re.match(r"(?is)^(?:CONSTRAINT\b|CHECK\b)", item)
        for parsed in [parse_constraint(item)]
        if parsed["type"] == "CHECK" and parsed["name"] == constraint_name
    ]
    if len(matches) != 1:
        raise AssertionError(
            f"expected exactly one {table_name}.{constraint_name} CHECK")
    return matches[0]


def validate_baseline_contract(
        expected_document: dict[str, Any],
        baseline_sources: dict[str, str]) -> None:
    expected_contract = expected_document["contract"]
    baseline_contract = extract_contract_from_reader(
        lambda relative_path: baseline_sources[relative_path])
    validate_contract_shape(baseline_contract)

    adjustment = expected_document["provenance"]["baselineAdjustments"][0]
    source_check = named_check(
        baseline_sources[adjustment["source"]["path"]],
        adjustment["source"]["table"],
        adjustment["source"]["constraintName"],
    )
    target_checks = [
        constraint
        for constraint in expected_contract["h2"]["tables"][
            adjustment["target"]["table"]
        ]["constraints"]
        if (
            constraint["type"] == "CHECK"
            and constraint["name"] == adjustment["target"]["constraintName"]
        )
    ]
    if len(target_checks) != 1:
        raise AssertionError("expected H2 period CHECK mirror is missing or duplicated")
    target_check = target_checks[0]
    if (
            source_check["expression"] != adjustment["expression"]
            or target_check["expression"] != adjustment["expression"]):
        raise AssertionError(
            "V4/H2 period CHECK mirror is not semantically identical")

    normalized_expected = copy.deepcopy(expected_contract)
    target_constraints = normalized_expected["h2"]["tables"][
        adjustment["target"]["table"]
    ]["constraints"]
    normalized_expected["h2"]["tables"][
        adjustment["target"]["table"]
    ]["constraints"] = [
        constraint
        for constraint in target_constraints
        if not (
            constraint["type"] == "CHECK"
            and constraint["name"] == adjustment["target"]["constraintName"]
        )
    ]
    if len(target_constraints) - len(normalized_expected["h2"]["tables"][
            adjustment["target"]["table"]
    ]["constraints"]) != 1:
        raise AssertionError("H2 baseline adjustment did not remove exactly one CHECK")
    differences = diff_values(
        baseline_contract,
        normalized_expected,
        "$.acceptedBaselineContract",
    )
    if differences:
        raise AssertionError(
            "v2 expected differs from the real accepted baseline contract "
            "outside the one V4-proven H2 period CHECK mirror:\n"
            + "\n".join(differences))


def validate_governance(
        repo_root: Path,
        decision_path: Path,
        expected_path: Path) -> None:
    root = repo_root.resolve()
    decision = json.loads(decision_path.read_text(encoding="utf-8"))
    differences = diff_values(
        expected_supersession_decision(),
        decision,
        "$.supersessionDecision",
    )
    if differences:
        raise AssertionError(
            "v1 supersession decision differs from fixed governance:\n"
            + "\n".join(differences))
    for relative_path, expected_sha256 in V1_ARTIFACTS:
        candidate = root / relative_path
        if not candidate.is_file() or root not in candidate.resolve().parents:
            raise AssertionError(f"frozen v1 artifact is missing or escapes repo: {relative_path}")
        actual_sha256 = sha256_file(candidate)
        if actual_sha256 != expected_sha256:
            raise AssertionError(
                f"frozen v1 artifact SHA256 changed: {relative_path}: "
                f"{actual_sha256} != {expected_sha256}")
    expected_document = json.loads(expected_path.read_text(encoding="utf-8"))
    validate_fixture_document(expected_document)
    resolved_commit = run_checkpoint_git(
        root,
        "rev-parse",
        "--verify",
        f"{ACCEPTED_BASELINE_COMMIT}^{{commit}}",
    ).decode("ascii").strip()
    if resolved_commit != ACCEPTED_BASELINE_COMMIT:
        raise AssertionError(
            "accepted baseline did not resolve to the required commit: "
            f"{resolved_commit}")

    baseline_bytes: dict[str, bytes] = {}
    for declaration in expected_document["provenance"]["acceptedBaselineBlobs"]:
        relative_path = declaration["path"]
        object_id, content = checkpoint_blob(
            root,
            ACCEPTED_BASELINE_COMMIT,
            relative_path,
        )
        if object_id != declaration["gitBlobSha1"]:
            raise AssertionError(
                f"accepted baseline blob OID changed: {relative_path}: "
                f"{object_id} != {declaration['gitBlobSha1']}")
        actual_sha256 = hashlib.sha256(content).hexdigest()
        if actual_sha256 != declaration["sha256"]:
            raise AssertionError(
                f"accepted baseline blob SHA256 changed: {relative_path}: "
                f"{actual_sha256} != {declaration['sha256']}")
        baseline_bytes[relative_path] = content

    contract_paths = (*JAVA_FILES, MAPPER_XML, H2_SCHEMA, OPENAPI)
    for relative_path in contract_paths:
        if relative_path not in baseline_bytes:
            _, baseline_bytes[relative_path] = checkpoint_blob(
                root,
                ACCEPTED_BASELINE_COMMIT,
                relative_path,
            )
    baseline_sources = {
        relative_path: content.decode("utf-8")
        for relative_path, content in baseline_bytes.items()
    }
    validate_baseline_contract(expected_document, baseline_sources)
    sys.stdout.write(
        "W2_PUBLIC_CONTRACT_V1_SUPERSESSION=PASS "
        "status=INVALIDATED_SUPERSEDED v1Integrity=PASS "
        f"authoritativeBaseline={ACCEPTED_BASELINE_COMMIT} "
        "checkpointCommit=PASS baselineBlobs=7 baselineContract=PASS "
        "h2Adjustment=V4-PERIOD-CHECK-H2-SYNONYMOUS-MIRROR "
        "currentGate=v2\n")


def verify_contract(actual_path: Path, expected_path: Path) -> None:
    actual = json.loads(actual_path.read_text(encoding="utf-8"))
    expected_document = json.loads(expected_path.read_text(encoding="utf-8"))
    expected = validate_fixture_document(expected_document)
    validate_contract_shape(actual)
    validate_contract_shape(expected)
    if actual["boundary"]["violations"]:
        raise AssertionError(
            "forbidden W3 token entered W2 surface: "
            + json.dumps(
                actual["boundary"]["violations"],
                ensure_ascii=False,
                sort_keys=True,
            ))
    differences = diff_values(expected, actual)
    if differences:
        raise AssertionError(
            "W2 public contract v2 exact diff failed:\n"
            + "\n".join(differences))
    template_get = next(
        operation
        for operation in actual["openapi"]["operations"]
        if operation["method"] == "get"
        and operation["path"] == "/policy-templates/{templateId}"
    )
    parameter_names = [
        parameter["name"] for parameter in template_get["parameters"]
    ]
    if parameter_names != ["templateId"]:
        raise AssertionError(
            "GET /policy-templates/{templateId} parameters must be [templateId]")
    scope_type = next(
        type_entry
        for file_entry in actual["java"].values()
        for type_entry in flatten_types(file_entry["types"])
        if type_entry["qualifiedName"] == "PolicyModels.ScopeType"
    )
    if "ATTENDANCE_GROUP" not in scope_type["enumConstants"]:
        raise AssertionError("legal W2 ScopeType.ATTENDANCE_GROUP is absent")
    sys.stdout.write(json.dumps({
        "gate": "w2-public-contract-v2",
        "status": "PASS",
        "javaFiles": len(actual["java"]),
        "mapperStatements": len(
            actual["mapper"]["statementClosure"]["xmlStatementIds"]),
        "openapiOperations": len(actual["openapi"]["operations"]),
        "h2Tables": len(actual["h2"]["tables"]),
        "attendanceGroupScope": "retained",
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")


def copy_minimal_source(source_root: Path, target_root: Path) -> None:
    paths = (*JAVA_FILES, MAPPER_XML, H2_SCHEMA, OPENAPI)
    if len(paths) != len(set(paths)):
        raise AssertionError("duplicate v2 source allowlist entry")
    for relative_path in paths:
        source = source_root / relative_path
        target = target_root / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text(encoding="utf-8")
    occurrences = source.count(old)
    if occurrences != 1:
        raise AssertionError(
            f"mutation anchor count for {path} is {occurrences}, expected 1: {old!r}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


def mutate_template_detail_visibility(root: Path) -> None:
    replace_once(
        root / JAVA_FILES[16],
        "    static TemplateDetail templateDetail(PolicyTemplate template) {",
        "    public static TemplateDetail templateDetail(PolicyTemplate template) {",
    )


def mutate_template_page_visibility(root: Path) -> None:
    replace_once(
        root / JAVA_FILES[16],
        "    static TemplatePage templatePage(Page<PolicyTemplate> page) {",
        "    public static TemplatePage templatePage(Page<PolicyTemplate> page) {",
    )


def mutate_version_detail_visibility(root: Path) -> None:
    replace_once(
        root / JAVA_FILES[16],
        "    static VersionDetail versionDetail(PolicyVersion version) {",
        "    public static VersionDetail versionDetail(PolicyVersion version) {",
    )


def mutate_version_page_visibility(root: Path) -> None:
    replace_once(
        root / JAVA_FILES[16],
        "    static VersionPage versionPage(Page<PolicyVersion> page) {",
        "    public static VersionPage versionPage(Page<PolicyVersion> page) {",
    )


def mutate_validation_visibility(root: Path) -> None:
    replace_once(
        root / JAVA_FILES[16],
        "    static ValidationResultDto validation(ValidationResult result) {",
        "    public static ValidationResultDto validation(ValidationResult result) {",
    )


def mutate_conflicts_visibility(root: Path) -> None:
    replace_once(
        root / JAVA_FILES[16],
        "    static ConflictResult conflicts(List<PolicyConflict> conflicts) {",
        "    public static ConflictResult conflicts(List<PolicyConflict> conflicts) {",
    )


def mutate_openapi_policy_version_detail_inline(root: Path) -> None:
    path = root / OPENAPI
    document = load_unique_yaml(path.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    detail = schemas["PolicyVersionDetail"]
    if set(detail) != {"allOf"} or len(detail["allOf"]) != 2:
        raise AssertionError("PolicyVersionDetail allOf mutation anchor differs")
    summary = copy.deepcopy(schemas["PolicyVersionSummary"])
    extension = detail["allOf"][1]
    summary["properties"].update(copy.deepcopy(extension["properties"]))
    summary["required"] = [
        *summary.get("required", []),
        *extension.get("required", []),
    ]
    schemas["PolicyVersionDetail"] = summary
    path.write_text(
        yaml.safe_dump(document, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )


def mutate_openapi_legal_entity(root: Path) -> None:
    path = root / OPENAPI
    document = load_unique_yaml(path.read_text(encoding="utf-8"))
    parameters = document["paths"]["/policy-templates/{templateId}"]["get"]["parameters"]
    parameters.append({"$ref": "#/components/parameters/AttendanceLegalEntityId"})
    path.write_text(
        yaml.safe_dump(
            document,
            allow_unicode=True,
            sort_keys=False,
        ),
        encoding="utf-8",
    )


def mutate_repository_lock(root: Path) -> None:
    path = root / (
        "backend/src/main/java/com/szsemicon/hr/policy/application/"
        "PolicyRepository.java")
    replace_once(
        path,
        "    boolean templateCodeExists(String code);",
        "    void lockTemplate(String templateId);\n\n"
        "    boolean templateCodeExists(String code);",
    )


def mutate_validation_attendance(root: Path) -> None:
    path = root / (
        "backend/src/main/java/com/szsemicon/hr/policy/application/"
        "PolicyValidationService.java")
    replace_once(
        path,
        "        return issues;\n    }\n\n"
        "    public ValidationResult validateVersion(",
        "        validateAttendanceTemplate(code, fields, issues);\n"
        "        return issues;\n"
        "    }\n\n"
        "    public ValidationResult validateVersion(",
    )
    source = path.read_text(encoding="utf-8")
    closing = source.rfind("}")
    helper = (
        "\n    private void validateAttendanceTemplate(\n"
        "            String code,\n"
        "            List<FieldDefinition> fields,\n"
        "            List<ValidationIssue> issues) {\n"
        "        if (\"ATTENDANCE_LATE_GRACE\".equals(code)) {\n"
        "            issues.add(issue(\"ATTENDANCE\", \"fieldDefinitions\", "
        "\"forbidden specialization\"));\n"
        "        }\n"
        "    }\n"
    )
    path.write_text(source[:closing] + helper + source[closing:], encoding="utf-8")


def mutate_mapper_java(root: Path) -> None:
    path = root / (
        "backend/src/main/java/com/szsemicon/hr/policy/infrastructure/"
        "persistence/PolicyMapper.java")
    replace_once(
        path,
        "    long countTemplateCode(@Param(\"code\") String code);",
        "    long countTemplateCodes(@Param(\"code\") String code);",
    )


def mutate_policy_rows(root: Path) -> None:
    path = root / (
        "backend/src/main/java/com/szsemicon/hr/policy/infrastructure/"
        "persistence/PolicyRows.java")
    replace_once(path, "            int latestVersionNumber,", "            long latestVersionNumber,")


def mutate_mapper_xml_id(root: Path) -> None:
    replace_once(
        root / MAPPER_XML,
        '<select id="countTemplateCode" resultType="long">',
        '<select id="countTemplateCodes" resultType="long">',
    )


def mutate_mapper_xml_lock_template(root: Path) -> None:
    path = root / MAPPER_XML
    replace_once(
        path,
        "</mapper>",
        '    <select id="lockTemplate" resultType="string">\n'
        "        SELECT template_id\n"
        "        FROM policy_template\n"
        "        WHERE template_id = #{templateId}\n"
        "        FOR UPDATE\n"
        "    </select>\n"
        "</mapper>",
    )


def mutate_mapper_sql_column_order(root: Path) -> None:
    replace_once(
        root / MAPPER_XML,
        "        INSERT INTO policy_template (\n"
        "            template_id,\n"
        "            template_code,",
        "        INSERT INTO policy_template (\n"
        "            template_code,\n"
        "            template_id,",
    )


def mutate_mapper_sql_where(root: Path) -> None:
    replace_once(
        root / MAPPER_XML,
        "        WHERE template_code = #{code}",
        "        WHERE template_id = #{code}",
    )


def mutate_mapper_for_update(root: Path) -> None:
    replace_once(
        root / MAPPER_XML,
        "        WHERE template_id = #{templateId}\n"
        "          AND version_id = #{versionId}\n"
        "    </select>",
        "        WHERE template_id = #{templateId}\n"
        "          AND version_id = #{versionId}\n"
        "        FOR UPDATE\n"
        "    </select>",
    )


def mutate_h2_column(root: Path) -> None:
    replace_once(
        root / H2_SCHEMA,
        "CREATE TABLE policy_template (\n"
        "    template_id VARCHAR(36) PRIMARY KEY,\n"
        "    template_code VARCHAR(64) NOT NULL UNIQUE,\n"
        "    name VARCHAR(100) NOT NULL,",
        "CREATE TABLE policy_template (\n"
        "    template_id VARCHAR(36) PRIMARY KEY,\n"
        "    template_code VARCHAR(64) NOT NULL UNIQUE,\n"
        "    name VARCHAR(101) NOT NULL,",
    )


def mutate_h2_unique(root: Path) -> None:
    replace_once(
        root / H2_SCHEMA,
        "    UNIQUE (template_id, version_number),",
        "    UNIQUE (version_number, template_id),",
    )


def mutate_h2_check(root: Path) -> None:
    replace_once(
        root / H2_SCHEMA,
        "        CHECK (effective_to IS NULL OR effective_to >= effective_from)",
        "        CHECK (effective_to IS NULL OR effective_to > effective_from)",
    )


def mutate_h2_index(root: Path) -> None:
    replace_once(
        root / H2_SCHEMA,
        "    ON policy_version (template_id, status, effective_from, effective_to);",
        "    ON policy_version (status, template_id, effective_from, effective_to);",
    )


def run_self_test(
        repo_root: Path,
        extractor: Path,
        expected: Path) -> None:
    mutations: list[tuple[str, str, str, Callable[[Path], None]]] = [
        (
            "policy-dtos-template-detail-public",
            JAVA_FILES[16],
            "java.helper-visibility",
            mutate_template_detail_visibility,
        ),
        (
            "policy-dtos-template-page-public",
            JAVA_FILES[16],
            "java.helper-visibility",
            mutate_template_page_visibility,
        ),
        (
            "policy-dtos-version-detail-public",
            JAVA_FILES[16],
            "java.helper-visibility",
            mutate_version_detail_visibility,
        ),
        (
            "versionPage-public",
            JAVA_FILES[16],
            "java.helper-visibility",
            mutate_version_page_visibility,
        ),
        (
            "validation-public",
            JAVA_FILES[16],
            "java.helper-visibility",
            mutate_validation_visibility,
        ),
        (
            "conflicts-public",
            JAVA_FILES[16],
            "java.helper-visibility",
            mutate_conflicts_visibility,
        ),
        (
            "openapi-policy-version-detail-inline",
            OPENAPI,
            "openapi.schema-composition",
            mutate_openapi_policy_version_detail_inline,
        ),
        ("openapi-extra-legal-entity", OPENAPI, "openapi.parameters", mutate_openapi_legal_entity),
        ("repository-lock-template", JAVA_FILES[8], "java.boundary", mutate_repository_lock),
        ("validation-attendance-helper", JAVA_FILES[10], "java.boundary", mutate_validation_attendance),
        ("mapper-java-method", JAVA_FILES[13], "java.mapper-signature", mutate_mapper_java),
        ("policy-rows-component", JAVA_FILES[14], "java.record-component", mutate_policy_rows),
        ("mapper-xml-id", MAPPER_XML, "mapper.statement-id", mutate_mapper_xml_id),
        (
            "mapper-xml-lock-template",
            MAPPER_XML,
            "mapper.boundary",
            mutate_mapper_xml_lock_template,
        ),
        ("mapper-sql-column-order", MAPPER_XML, "mapper.sql-column-order", mutate_mapper_sql_column_order),
        ("mapper-sql-where", MAPPER_XML, "mapper.sql-where", mutate_mapper_sql_where),
        ("h2-column", H2_SCHEMA, "h2.column", mutate_h2_column),
        ("h2-unique", H2_SCHEMA, "h2.unique", mutate_h2_unique),
        ("h2-check", H2_SCHEMA, "h2.check", mutate_h2_check),
        ("h2-index", H2_SCHEMA, "h2.index", mutate_h2_index),
    ]
    with tempfile.TemporaryDirectory(prefix="w2-public-v2-self-test-") as temporary:
        temporary_root = Path(temporary)
        base = temporary_root / "base"
        copy_minimal_source(repo_root, base)
        positive_actual = temporary_root / "positive.json"
        positive_extract = subprocess.run(
            [str(extractor), str(base.resolve()), str(positive_actual)],
            check=False,
            capture_output=True,
            text=True,
        )
        if positive_extract.returncode != 0:
            raise AssertionError(
                "v2 positive production extractor failed:\n"
                + positive_extract.stdout + positive_extract.stderr)
        positive_verify = subprocess.run(
            [
                sys.executable,
                str(Path(__file__).resolve()),
                "--actual",
                str(positive_actual),
                "--expected",
                str(expected),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if positive_verify.returncode != 0:
            raise AssertionError(
                "v2 positive production verifier failed:\n"
                + positive_verify.stdout + positive_verify.stderr)
        for name, source_file, surface, mutation in mutations:
            mutant_root = temporary_root / name
            shutil.copytree(base, mutant_root)
            mutation(mutant_root)
            actual = temporary_root / f"{name}.json"
            extract_result = subprocess.run(
                [str(extractor), str(mutant_root.resolve()), str(actual)],
                check=False,
                capture_output=True,
                text=True,
            )
            if extract_result.returncode != 0:
                raise AssertionError(
                    f"mutation {name} killed only by extraction failure:\n"
                    + extract_result.stdout + extract_result.stderr)
            verify_result = subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).resolve()),
                    "--actual",
                    str(actual),
                    "--expected",
                    str(expected),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            if verify_result.returncode == 0:
                raise AssertionError(f"source mutation unexpectedly passed: {name}")
            sys.stdout.write(
                "W2_V2_MUTATION_KILLED "
                f"name={name} source={source_file} surface={surface} "
                f"verifierExit={verify_result.returncode}\n")
    sys.stdout.write(
        "W2_PUBLIC_CONTRACT_V2_SELF_TEST=PASS "
        f"productionPath=true sourceMutations={len(mutations)}\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--extract", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    mode.add_argument("--governance", action="store_true")
    mode.add_argument("--actual", type=Path)
    parser.add_argument("--repo", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--expected", type=Path)
    parser.add_argument("--extractor", type=Path)
    parser.add_argument("--decision", type=Path)
    arguments = parser.parse_args()
    if arguments.governance:
        if (
                arguments.repo is None
                or arguments.expected is None
                or arguments.decision is None):
            parser.error("--governance requires --repo, --expected and --decision")
        validate_governance(
            arguments.repo,
            arguments.decision,
            arguments.expected,
        )
        return
    if arguments.extract:
        if arguments.repo is None or arguments.output is None:
            parser.error("--extract requires --repo and --output")
        write_contract(extract_contract(arguments.repo), arguments.output)
        return
    if arguments.self_test:
        if (
                arguments.repo is None
                or arguments.expected is None
                or arguments.extractor is None):
            parser.error("--self-test requires --repo, --expected and --extractor")
        run_self_test(
            arguments.repo.resolve(),
            arguments.extractor.resolve(),
            arguments.expected.resolve(),
        )
        return
    if arguments.actual is None or arguments.expected is None:
        parser.error("--actual requires --expected")
    verify_contract(arguments.actual, arguments.expected)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, ValueError, KeyError, json.JSONDecodeError) as exception:
        sys.stderr.write(f"W2_PUBLIC_CONTRACT_V2=FAIL {exception}\n")
        raise SystemExit(1)
