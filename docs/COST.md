# ClaudeDriver — Cost Review (small-fleet envelope)

Reviews the hosted footprint against the target scale (**≤10 machines / ≤25 concurrent sessions**,
single operator) per Constitution Principle VII and FR-011. Figures are rough monthly estimates for a
single low-traffic region; confirm against AWS Cost Explorer filtered by `Project=ClaudeDriver`.

## Always-on components

| Component | Sizing | Est. monthly | Notes |
|---|---|---|---|
| ECS Fargate (backend) | 1 task, 0.25 vCPU / 0.5 GB | ~$9 | Mostly-idle WS hub; one small task suffices at this scale |
| Application Load Balancer | 1 ALB, mTLS listener | ~$16–20 | **Justified**: only managed ingress giving agent mTLS (Principle I). Tracked in the plan's Complexity Tracking |
| Amazon RDS PostgreSQL | `db.t4g.micro`, single-AZ, 20 GB gp3 | ~$13 | Cheapest managed Postgres tier for a small fleet |
| SSM Parameter Store | SecureString params | ~$0 | Standard tier is free |
| CloudWatch Logs | short retention (14 d) | ~$1–3 | Bounded by low event volume |
| **Subtotal (always-on)** | | **~$40–45/mo** | |

## Usage-based components

| Component | Driver | Est. monthly | Notes |
|---|---|---|---|
| Amazon SNS mobile push | per notification | ~$0 | Tiny volume for one operator |
| Data transfer | small | ~$1–2 | Outbound to clients/agents |
| Managed-mode model usage | Claude API tokens | variable | **Not a hosting cost** — billed to the operator's Claude account per managed session; scales with use, not with ClaudeDriver |

## Assessment

- Always-on hosting lands around **$40–45/month** for the target small fleet — within a reasonable
  self-hosted envelope. The **ALB is the single largest fixed line** and is the one justified
  exception to raw cost-minimization (it provides agent mTLS).
- Everything scales sub-linearly with the fleet at this size (one Fargate task + one small RDS serve
  ≤10 machines comfortably); event volume is bounded by coalescing + bounded queues (Principle V/VII).
- **Cost levers if needed**: drop the ALB by fronting with an NLB + app-terminated mTLS (more ops);
  use Aurora Serverless v2 only if load grows; scale the Fargate task to zero on idle if long
  quiet periods are common (App Runner alternative — trades away ALB mTLS, see the plan).

## Action

Set an AWS Budget alert (already in `infra/governance.tf`) at ~1.5× the expected subtotal so
unexpected always-on cost surfaces immediately.
