"""Minimal text-protocol client for an isolated, disposable local MySQL test instance."""
import json
import socket
import struct
import time
from pathlib import Path


class MySQLError(RuntimeError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(f'MySQL {code}: {message}')


class MySQL:
    def __init__(self, port):
        self.sock = socket.create_connection(('127.0.0.1', int(port)), timeout=20)
        self.sock.settimeout(30)
        greeting = self.read()
        self.check_error(greeting)
        self.server_version = greeting[1:greeting.index(b'\0', 1)].decode()
        caps = 0x1 | 0x4 | 0x200 | 0x2000 | 0x8000 | 0x80000
        login = struct.pack('<IIB', caps, 16 * 1024 * 1024, 45) + bytes(23)
        login += b'codex_validation\0\0mysql_native_password\0'
        self.write(login, 1)
        auth = self.read()
        if auth[0] == 0xfe:
            self.write(b'', 3)
            auth = self.read()
        self.check_error(auth)
        if auth[0] != 0:
            raise RuntimeError(f'Unexpected local authentication response: {auth[:8].hex()}')

    def recv(self, length):
        chunks = []
        while length:
            chunk = self.sock.recv(length)
            if not chunk:
                raise ConnectionError('Local MySQL closed the connection')
            chunks.append(chunk)
            length -= len(chunk)
        return b''.join(chunks)

    def read(self):
        header = self.recv(4)
        size = int.from_bytes(header[:3], 'little')
        return self.recv(size)

    def write(self, payload, seq=0):
        self.sock.sendall(len(payload).to_bytes(3, 'little') + bytes([seq]) + payload)

    @staticmethod
    def check_error(payload):
        if payload[0] == 0xff:
            offset = 9 if payload[3:4] == b'#' else 3
            raise MySQLError(int.from_bytes(payload[1:3], 'little'), payload[offset:].decode('utf-8', 'replace'))

    @staticmethod
    def length_encoded(payload, offset=0):
        first = payload[offset]
        offset += 1
        if first < 0xfb:
            return first, offset
        if first == 0xfb:
            return None, offset
        width = {0xfc: 2, 0xfd: 3, 0xfe: 8}[first]
        return int.from_bytes(payload[offset:offset+width], 'little'), offset + width

    def query(self, sql):
        self.write(b'\x03' + sql.encode('utf-8'))
        first = self.read()
        self.check_error(first)
        if first[0] == 0:
            affected, pos = self.length_encoded(first, 1)
            insert_id, pos = self.length_encoded(first, pos)
            return {'affected': affected, 'insertId': insert_id, 'rows': []}
        count, _ = self.length_encoded(first)
        for _ in range(count):
            self.check_error(self.read())
        end = self.read()
        if end[0] != 0xfe:
            raise RuntimeError('Expected result metadata EOF')
        rows = []
        while True:
            row = self.read()
            self.check_error(row)
            if row[0] == 0xfe and len(row) < 9:
                break
            values = []
            pos = 0
            for _ in range(count):
                length, pos = self.length_encoded(row, pos)
                if length is None:
                    values.append(None)
                else:
                    values.append(row[pos:pos+length].decode('utf-8', 'replace'))
                    pos += length
            rows.append(values)
        return {'rows': rows}

    def close(self):
        try:
            self.write(b'\x01')
        finally:
            self.sock.close()


def statements(text):
    """Split owned schema SQL, respecting strings, escaped quotes, identifiers and comments."""
    result = []
    current = []
    quote = None
    line_comment = False
    block_comment = False
    index = 0
    while index < len(text):
        ch = text[index]
        nxt = text[index+1:index+2]
        if line_comment:
            if ch == '\n':
                line_comment = False
                current.append('\n')
        elif block_comment:
            if ch == '*' and nxt == '/':
                block_comment = False
                index += 1
                current.append(' ')
        elif quote:
            current.append(ch)
            if ch == '\\' and nxt:
                current.append(nxt)
                index += 1
            elif ch == quote:
                if nxt == quote:
                    current.append(nxt)
                    index += 1
                else:
                    quote = None
        elif ch in "'\"`":
            quote = ch
            current.append(ch)
        elif ch == '#' or (ch == '-' and nxt == '-' and (index+2 >= len(text) or text[index+2].isspace())):
            line_comment = True
            current.append(' ')
            if ch == '-':
                index += 1
        elif ch == '/' and nxt == '*':
            block_comment = True
            current.append(' ')
            index += 1
        elif ch == ';':
            sql = ''.join(current).strip()
            if sql:
                result.append(sql)
            current = []
        else:
            current.append(ch)
        index += 1
    if quote or block_comment:
        raise ValueError('Unterminated SQL literal or block comment')
    final = ''.join(current).strip()
    if final:
        result.append(final)
    return result


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--sql')
    parser.add_argument('--query')
    parser.add_argument('--report')
    args = parser.parse_args()
    base = Path(__file__).resolve().parent
    port = (base/'validation-port.txt').read_text().strip()
    conn = MySQL(port)
    report = {'serverVersion': conn.server_version, 'passed': 0, 'errors': []}
    if args.query:
        print(json.dumps(conn.query(args.query), ensure_ascii=False))
    if args.sql:
        items = statements(Path(args.sql).read_text('utf-8-sig'))
        start = time.monotonic()
        for i, sql in enumerate(items, 1):
            try:
                conn.query(sql)
                report['passed'] += 1
            except Exception as exc:
                report['errors'].append({'statement': i, 'sql': sql, 'error': str(exc)})
                break
        report['statementCount'] = len(items)
        report['elapsedSeconds'] = round(time.monotonic()-start, 3)
        if args.report:
            Path(args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False))
    conn.close()
