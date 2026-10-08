#!/usr/bin/env python3
"""
Cross-checks the JPA entities against database/01_schema.sql.

Hibernate runs with ddl-auto=none, so the SQL file is the only thing that
creates tables. That makes a mismatch between an @Column name and the real
column a runtime failure - and one that only shows up when that particular
query runs, which may be well after startup. A rename on one side and not
the other is exactly the kind of drift this catches.

Checks, per @Entity:
  1. the @Table(name=...) exists in the schema;
  2. every persistent field maps to a column that exists;
  3. no column in the table is left unmapped by any field
     (generated/derived columns are allowed to be, and are listed).

Field -> column resolution follows Hibernate's default strategy as
configured here: explicit @Column(name=...) wins, otherwise the field name
is converted from camelCase to snake_case.

Run with --inject to confirm the checker actually fails on a planted
mismatch.
"""
import re
import sys
import pathlib

PROJECT = pathlib.Path(__file__).resolve().parent.parent
ENTITY_DIR = PROJECT / 'src/main/java/com/sliit/eventsphere/entity'
SCHEMA = PROJECT / 'database/01_schema.sql'

# Columns the database fills in or derives; no field is expected to map them.
GENERATED_OK = {'active_user_id'}


def snake(name: str) -> str:
    return re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower()


def strip_comments(sql: str) -> str:
    sql = re.sub(r'--[^\n]*', '', sql)
    return re.sub(r'/\*.*?\*/', '', sql, flags=re.S)


def parse_schema(sql: str) -> dict:
    """table name -> set of column names."""
    sql = strip_comments(sql)
    tables = {}
    for m in re.finditer(r'CREATE\s+TABLE\s+(\w+)\s*\((.*?)\)\s*ENGINE', sql, re.S | re.I):
        table, body = m.group(1), m.group(2)
        cols = set()
        depth = 0
        line_start = 0
        parts = []
        for i, ch in enumerate(body):
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            elif ch == ',' and depth == 0:
                parts.append(body[line_start:i])
                line_start = i + 1
        parts.append(body[line_start:])
        for part in parts:
            part = part.strip()
            if not part:
                continue
            first = part.split()[0].upper()
            if first in ('PRIMARY', 'UNIQUE', 'KEY', 'INDEX', 'CONSTRAINT', 'FOREIGN', 'CHECK'):
                continue
            cols.add(part.split()[0].strip('`'))
        tables[table] = cols
    return tables


def parse_entities() -> dict:
    """entity file -> (table, {field: column})."""
    entities = {}
    for f in sorted(ENTITY_DIR.glob('*.java')):
        src = f.read_text()
        if '@Entity' not in src:
            continue
        table_m = re.search(r'@Table\s*\(\s*name\s*=\s*"(\w+)"', src)
        table = table_m.group(1) if table_m else snake(f.stem) + 's'

        mapping = {}
        # Each field declaration, with whatever annotations precede it.
        for m in re.finditer(
                r'((?:@\w+(?:\([^)]*\))?\s*)*)private\s+(?:final\s+)?[\w.<>\[\], ]+?\s+(\w+)\s*[=;]',
                src):
            annotations, field = m.group(1), m.group(2)
            if '@Transient' in annotations:
                continue
            if '@OneToMany' in annotations or '@ManyToMany' in annotations:
                continue          # inverse side; no column on this table
            name_m = re.search(r'@(?:Column|JoinColumn)\s*\([^)]*name\s*=\s*"(\w+)"', annotations)
            if name_m:
                mapping[field] = name_m.group(1)
            elif '@ManyToOne' in annotations or '@OneToOne' in annotations:
                mapping[field] = snake(field) + '_id'
            else:
                mapping[field] = snake(field)
        entities[f] = (table, mapping)
    return entities


def main() -> int:
    sql = SCHEMA.read_text()
    if '--inject' in sys.argv:
        # Plant the exact drift this exists to catch: a column renamed in
        # the schema but not in the entity.
        sql = sql.replace('token_hash      CHAR(64)', 'reset_token     CHAR(64)')
        print("[--inject] renamed password_reset_tokens.token_hash in the parsed schema\n")

    tables = parse_schema(sql)
    problems = []
    notes = []

    for f, (table, mapping) in parse_entities().items():
        if table not in tables:
            problems.append(f"{f.name}: @Table \"{table}\" has no CREATE TABLE in the schema")
            continue
        columns = tables[table]
        for field, column in sorted(mapping.items()):
            if column not in columns:
                problems.append(
                    f"{f.name}: field '{field}' maps to {table}.{column}, "
                    f"which does not exist (columns: {', '.join(sorted(columns))})")
        unmapped = columns - set(mapping.values()) - GENERATED_OK
        if unmapped:
            notes.append(f"{table}: column(s) not mapped by any field: {', '.join(sorted(unmapped))}")

    for note in notes:
        print(f"note: {note}")

    if problems:
        print()
        for p in problems:
            print(p)
        print(f"\n{len(problems)} mismatch(es)")
        return 1

    print(f"\nPASS - {len(tables)} table(s); every entity field maps to a real column")
    return 0


if __name__ == '__main__':
    sys.exit(main())
