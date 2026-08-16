# ClaudeDriver Phase 0 — AWS Infrastructure (Terraform)

Provisions the Phase 0 "walking skeleton" per the ratified decisions in
`specs/001-phase-0-foundations/` and the constitution (v1.1.0):

- **ECS Fargate** — one small task (256 CPU / 512 MB), `desired_count = 1`.
- **Application Load Balancer** with two HTTPS listeners:
  - `:443` operator/web — server TLS only; operators authenticate with
    self-hosted **WebAuthn passkeys** at the app layer (decision D4).
  - `:8443` agent — **mutual TLS**; the ALB verifies each agent's client
    certificate against the device-CA **trust store** (decisions D2/D3,
    Principles I/IV).
- **RDS PostgreSQL** `db.t4g.micro`, single-AZ, `rds.force_ssl` + client
  `sslmode=require` (decision D6).
- **SSM Parameter Store** SecureString secrets under `/claudedriver/*` (D8).
- **Least-privilege IAM** task execution + task roles (SSM read scoped to
  `/claudedriver/*`, own log stream only).
- **Cost attribution** — provider `default_tags` stamp `Project=ClaudeDriver`
  and `Environment` on every resource (FR-021/FR-022), plus an AWS Resource
  Group and a monthly AWS **Budget** with email alerts (Principle VII, D9).

> **Security gate — do not skip.** This stack is a remote-code-execution control
> plane over a developer fleet. It MUST NOT be exposed until the mTLS agent
> listener and WebAuthn operator hardening are in place and verified. The agent
> listener is only as safe as the CA bundle in the trust store — never populate
> it with a CA that can sign certificates outside your enrollment flow.

## Cost note (the one justified always-on expense)

The ALB (~$16–20/mo fixed) is the single tracked cost tradeoff: it is the only
managed AWS ingress that performs client-certificate mTLS, which Principle I
requires for agent device identity. See the plan's Complexity Tracking.

## Prerequisites

Before `terraform apply` you (the operator) must supply resources this stack
references but does not create:

1. **AWS credentials** with permission to manage ECS, ELBv2, RDS, IAM, SSM,
   Budgets, and Resource Groups (e.g. `export AWS_PROFILE=...` /
   `AWS_ACCESS_KEY_ID`+`AWS_SECRET_ACCESS_KEY`, plus `AWS_REGION`).
2. **An ACM certificate** for your `domain_name`, validated in the target
   region → pass its ARN as `acm_certificate_arn`. Used by both listeners.
3. **The device-CA trust bundle** (PEM) uploaded to S3 → set
   `agent_ca_bundle_s3_bucket` / `agent_ca_bundle_s3_key`. This is the CA whose
   certificates the ALB will accept for agent mTLS. Example upload:
   ```sh
   aws s3 cp agent-ca-bundle.pem \
     s3://<your-bucket>/claudedriver/agent-ca-bundle.pem
   ```
4. **A backend container image** pushed to a registry (e.g. ECR) → pass its
   reference as `container_image`. Build it from `infra/docker/Dockerfile`
   (run from the repo root so the Gradle multi-module context is present):
   ```sh
   ./gradlew :backend:installDist         # optional local sanity check
   docker build -f infra/docker/Dockerfile -t claudedriver-backend:latest .
   ```
5. A **DB password** provided out-of-band (never committed):
   `export TF_VAR_db_password='...'`.
6. A **budget alert email** → `budget_notification_email`.

## Variables you must set

| Variable | Required | Notes |
|---|---|---|
| `acm_certificate_arn` | yes | ACM cert ARN for the listeners (operator-supplied). |
| `domain_name` | yes | Public DNS name; must match the cert. |
| `agent_ca_bundle_s3_bucket` | yes | S3 bucket holding the device-CA PEM bundle. |
| `agent_ca_bundle_s3_key` | no (default) | Object key of the CA bundle. |
| `container_image` | yes | Backend image reference (ECR tag). |
| `db_password` | yes | Sensitive; via `TF_VAR_db_password`. |
| `budget_notification_email` | yes | Budget alert recipient. |
| `environment` / `aws_region` | no (defaults) | `dev` / `us-east-1`. |
| `monthly_budget_limit_usd` | no (default `50`) | Budget ceiling. |

Put non-secret values in a `terraform.tfvars` (git-ignored) and keep
`db_password` in the environment.

## Deploy

```sh
cd infra
terraform init
terraform plan   -out plan.out
terraform apply  plan.out
```

Then point DNS for `domain_name` at the `alb_dns_name` output (Route 53 ALIAS
using `alb_zone_id`, or a CNAME).

## How the mTLS trust store is populated

1. The backend's device CA (Bouncy Castle, issued at operator-approved
   enrollment — decision D3) is the root/intermediate that signs each agent's
   per-device client certificate.
2. Export that CA's certificate chain as a PEM **bundle** and upload it to the
   S3 location referenced by `agent_ca_bundle_s3_bucket` /
   `agent_ca_bundle_s3_key` **before apply**.
3. Terraform creates `aws_lb_trust_store.agents` from that S3 object and wires
   it into the agent listener's `mutual_authentication { mode = "verify" }`.
4. Agents dial the `:8443` listener presenting their client cert; the ALB
   rejects any cert not chaining to the trust store. **Revocation** is handled
   at the app layer (the backend maps a verified cert to a `Machine` and can
   refuse revoked identities); to also stop verification at the edge, re-upload
   an updated bundle and update the trust store.

## Verify cost tagging

- **Resource Groups:** open the `claudedriver` group
  (`resource_group_name` output) in the console, or:
  ```sh
  aws resource-groups list-group-resources --group-name claudedriver
  ```
  Every stack resource should appear (all carry `Project=ClaudeDriver`).
- **Cost Explorer:** activate the `Project` and `Environment` cost-allocation
  tags in Billing → Cost allocation tags, then filter Cost Explorer by
  `Project = ClaudeDriver` to see total project spend. (Tag activation can take
  up to 24h to appear in Cost Explorer.)
- **Budget:** the `claudedriver-monthly` budget is filtered to
  `Project=ClaudeDriver` and emails `budget_notification_email` at 80% actual
  and 100% forecasted.

## Notes / Phase 0 scope

- Networking reuses the **default VPC + default subnets** to stay cheap and
  simple for the ≤10-machine / ≤25-session fleet; a dedicated VPC is a later
  hardening step.
- `deletion_protection` and `skip_final_snapshot` on RDS are set for easy
  Phase 0 teardown — tighten these before any production data lands.
