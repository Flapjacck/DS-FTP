# CP372 Assignment 2: DS-FTP Protocol Report

## 1. SOT and EOT Packet Formats

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

## 2. Performance Comparison Table

### Test Configuration

- **Protocol Variants:** Stop-and-Wait (SAW), Go-Back-N (GBN-20, GBN-40, GBN-80)
- **File Sizes:** Small (< 4 KB), Large (0.2–2 MB)
- **Reliability Numbers:** RN = 0 (no loss), RN = 5 (every 5th ACK lost), RN = 100 (every 100th ACK lost)
- **Runs per Configuration:** 3 (results averaged)

### TODO: Performance Data

| Protocol | Window Size | File Size | RN = 0 | RN = 5 | RN = 100 | Notes |
|----------|------------|-----------|--------|--------|----------|-------|
| Stop-and-Wait | 1 | Small | TODO | TODO | TODO | Baseline |
| Stop-and-Wait | 1 | Large | TODO | TODO | TODO | Baseline |
| GBN | 20 | Small | TODO | TODO | TODO | |
| GBN | 20 | Large | TODO | TODO | TODO | |
| GBN | 40 | Small | TODO | TODO | TODO | |
| GBN | 40 | Large | TODO | TODO | TODO | |
| GBN | 80 | Small | TODO | TODO | TODO | |
| GBN | 80 | Large | TODO | TODO | TODO | |

**Metric:** Total transmission time in seconds (averaged over 3 runs)

### TODO: Analysis and Observations

- Discuss trends in performance as window size increases
- Analyze impact of ACK loss (RN variations) on transmission time
- Compare Stop-and-Wait vs. Go-Back-N efficiency
- Note any anomalies or unexpected results
