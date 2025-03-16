#!/usr/bin/env python3
import http.server
import socketserver
import socket
import logging
import sys
import threading

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    stream=sys.stdout
)

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

# This handler support PNA.
class PNARequestHandler(http.server.BaseHTTPRequestHandler):
    def do_OPTIONS(self):
        client_ip = self.client_address[0]
        logging.info(f"Received OPTIONS request from {client_ip} (with PNA support)")
        sys.stdout.flush()

        acrpn = self.headers.get("Access-Control-Request-Private-Network")

        response_body = b"{}"
        content_length = len(response_body)

        if acrpn and acrpn.lower() == "true":
            logging.info(f"Preflight valid from {client_ip}: 'Access-Control-Request-Private-Network: true' found.")
            sys.stdout.flush()
            self.send_response(200)
            self.send_header("Connection", "close")
            self.send_header("Content-Length", str(content_length))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Private-Network", "true")
            self.end_headers()
            self.wfile.write(response_body)
        else:
            logging.info(f"Preflight rejected from {client_ip}: Header missing or invalid.")
            sys.stdout.flush()
            self.send_response(403)
            self.send_header("Connection", "close")
            self.send_header("Content-Length", str(content_length))
            self.end_headers()
            self.wfile.write(response_body)

    def do_GET(self):
        client_ip = self.client_address[0]
        logging.info(f"Received GET request from {client_ip} for {self.path} (with PNA support)")
        sys.stdout.flush()
        response_text = f"This is a LAN device page WITH PNA support. Your IP: {client_ip}\n"
        response_body = response_text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(response_body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(response_body)

    def log_message(self, format, *args):
        logging.info("%s - - [%s] %s" % (
            self.client_address[0],
            self.log_date_time_string(),
            format % args
        ))
        sys.stdout.flush()

# This handler does NOT support PNA.
class BasicRequestHandler(http.server.BaseHTTPRequestHandler):
    def do_OPTIONS(self):
        client_ip = self.client_address[0]
        logging.info(f"Received OPTIONS request from {client_ip} (no PNA support)")
        sys.stdout.flush()
        response_body = b"{}"
        content_length = len(response_body)
        self.send_response(200)
        self.send_header("Connection", "close")
        self.send_header("Content-Length", str(content_length))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()
        self.wfile.write(response_body)

    def do_GET(self):
        client_ip = self.client_address[0]
        logging.info(f"Received GET request from {client_ip} for {self.path} (no PNA support)")
        sys.stdout.flush()
        response_text = f"This is a LAN device page WITHOUT PNA support. Your IP: {client_ip}\n"
        response_body = response_text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(response_body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(response_body)

    def log_message(self, format, *args):
        logging.info("%s - - [%s] %s" % (
            self.client_address[0],
            self.log_date_time_string(),
            format % args
        ))
        sys.stdout.flush()

def run_server(port, handler_class):
    with socketserver.TCPServer(("", port), handler_class) as httpd:
        logging.info(f"Server running on port {port}")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            logging.info(f"Server on port {port} is shutting down.")
        httpd.server_close()

if __name__ == "__main__":
    PORT1 = 8080  # Server with PNA support
    PORT2 = 8081  # Server without PNA support
    local_ip = get_local_ip()
    logging.info(f"Serving on IP {local_ip}. Port {PORT1}: with PNA; Port {PORT2}: without PNA")
    sys.stdout.flush()

    thread1 = threading.Thread(target=run_server, args=(PORT1, PNARequestHandler))
    thread2 = threading.Thread(target=run_server, args=(PORT2, BasicRequestHandler))

    thread1.start()
    thread2.start()

    thread1.join()
    thread2.join()
