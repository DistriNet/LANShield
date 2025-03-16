# LANShield with PNA protocol

This branch implements a Proof-of-Concept for the Private Network Access (PNA) protocol. The idea is to ensure that before any local HTTP request is allowed, the app first sends a preflight OPTIONS request to verify whether the destination supports PNA by sending the header Access-Control-Request-Private-Network. Only if the destination responds with the header Access-Control-Allow-Private-Network: true the connection is allowed, if Access-Control-Allow-Private-Network: false then the connection is blocked and a notification is issued. In the case the protocol is not supported, then we fall back to the default behaviour.

## Diagram about PNA

![mermaid-diagram-2025-03-16-190701](https://github.com/user-attachments/assets/bcc28a1c-aaa2-4317-90b5-086f7e1cd129)

## Key Changes

### 1. Plaintext HTTP Preflight Requests

- **Requirement:**  
  Since the defence relies on sending a preflight check over HTTP, plaintext traffic is unavoidable for this POC.

### 2. Preflight Request Implementation

- **PnaManager Class:**  
  A new class, PnaManager, sends preflight OPTIONS requests using HttpURLConnection. It sets the header Access-Control-Request-Private-Network: true and checks that the response code is 200 and that the response includes Access-Control-Allow-Private-Network set to "true".
  
- **Caching:**  
The results of the preflight request are cached by PnaCacheManager for 5 minutes (for both allowed and denied outcomes) to prevent sending duplicate preflight requests for the same IP:port combination.

### 3. Notification Management

- **Standard Notifications:**  
When a packet is forwarded (for example, when the effective global policy is ALLOW), a standard notification is shown using the existing mechanism. This notification also takes into account multicast and DNS filtering.

- **Preflight Failure Notifications:**  
If a preflight check fails, a preflight failure notification is posted.  
- **Keyed Notifications:**  
  For each destination (keyed by ip:port), only one notification is shown within the 5‑minute cache period.  
- **Timer-Based Removal:**  
  After the 5‑minute timeout, the key is removed from the active notification map so that a new notification can be posted if the preflight check fails again.

### 4. Testing the Defence

For testing, a controlled environment was set up to simulate local servers:

**Test Setup:**  
Two local test servers were configured to simulate destinations:
- **Server with PNA Support:**  
  Responds to OPTIONS requests with Access-Control-Allow-Private-Network: true.
- **Server without PNA Support:**  
  Responds without the PNA header.

**Test Observations:**  
- Preflight requests are sent to the server’s IP and port.
- When the server supports PNA (i.e. returns the header), the preflight succeeds and the connection is allowed without additional notifications.
- When the server does not support PNA, a preflight failure notification is posted. The caching mechanism ensures that only one notification is shown per IP:port until the 5‑minute timeout expires.
  
  ![image](https://github.com/user-attachments/assets/e1d541e3-ce39-4514-a988-5e04734720d3)

This POC demonstrates that the PNA protocol can be implemented gradually—allowing a step-by-step rollout across devices and servers—while ensuring that the system continues to function smoothly throughout the process.

Best regards,  
Nolan
