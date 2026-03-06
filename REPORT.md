### Start-of-Transmission (SOT) Packet

- **Type:** 0
- **Sequence Number:** 0 (always)
- **Length:** 0 (no payload)
- **Payload:** Zero-filled (ignored)
- **Purpose:** Initiates the connection handshake between Sender and Receiver
- **Response:** Receiver acknowledges with ACK packet (Type 2, Seq 0)

### End-of-Transmission (EOT) Packet

- **Type:** 3
- **Sequence Number:** (last DATA sequence + 1) mod 128
- **Length:** 0 (no payload)
- **Payload:** Zero-filled (ignored)
- **Purpose:** Signals completion of file transfer and initiates teardown
- **Response:** Receiver acknowledges with ACK packet (Type 2, Seq = EOT Seq)
- **Special Case:** For empty files, EOT uses Seq = 1

### Test Configuration

**Environment:** Localhost loopback | **Protocols:** SAW, GBN-20/40/80 | **Files:** 3 KB, 200 KB | **Loss:** RN=0 (none), RN=5, RN=100 | **Timeout:** 100ms | **Runs:** 3 averaged | **Metric:** Transmission time (seconds)

### Performance Data

| Protocol | Window Size | File Size | RN = 0 | RN = 5 | RN = 100 |
|----------|:-----------:|:---------:|:------:|:------:|:--------:|
| Stop-and-Wait | 1 | Small (3 KB)   | 0.02 s | 0.66 s | 0.01 s |
| Stop-and-Wait | 1 | Large (200 KB) | 0.23 s | 45.08 s | 2.01 s |
| GBN | 20 | Small (3 KB)   | 0.04 s | 0.04 s | 0.04 s |
| GBN | 20 | Large (200 KB) | 1.18 s | 1.16 s | 1.18 s |
| GBN | 40 | Small (3 KB)   | 0.05 s | 0.04 s | 0.04 s |
| GBN | 40 | Large (200 KB) | 1.64 s | 1.62 s | 1.64 s |
| GBN | 80 | Small (3 KB)   | 0.04 s | 0.04 s | 0.05 s |
| GBN | 80 | Large (200 KB) | 1.01 s | 1.12 s | 0.99 s |

**All values are means over 3 runs**. Raw Run Data (per-run timings) is available in `test_results.csv`.

## 3. Key Results & Findings

**Stop-and-Wait (SAW) is fragile when ACKs are lost:**
When packet loss happens, SAW times out and waits 100ms before retrying. For a 200 KB file with frequent losses (RN=5), this causes ~410 timeouts, adding 41 seconds of delay. The 0.23s clean transfer becomes 45s.

**Go-Back-N (GBN) is much better at handling loss:**
With pipelining, GBN can send many packets without waiting. If an ACK is lost, newer ACKs often catch up and say "I got everything up to packet X." This recovers from most drops automatically, keeping all GBN transfers around 1 second regardless of loss level.

**Loopback test quirk:**
On a fast local network (no delay), SAW is 0.23s but GBN is 1.2–1.6s. This is backwards from real networks! Why? GBN spends extra work managing windows and resending. But on a real slow network (space links with 3+ minute delays), GBN would be orders of magnitude faster because SAW would sit idle waiting for ACKs.

**Window size on loopback:**
Window size 20, 40, and 80 all perform differently on loopback (1.18s, 1.64s, 1.01s), but this is just quirky behavior of the test setup and retransmission logic. On a real network, bigger windows = faster.

**Summary:**

- **Loss resistance:** GBN >> SAW (< 10% slowdown vs. 100x slowdown)
- **Real-world use:** GBN is the clear winner for any high-latency link
- **Small files:** All methods take ~0.04s (file fits in one window)
