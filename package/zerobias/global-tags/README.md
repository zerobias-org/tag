# Global Tags

The platform's default tags, available to every org. Tags label resources (products, queries, tasks,
connections and so on) so they can be grouped, filtered and searched.

Each folder of this package is a **tag type**, and each `.yml` file in it is one **tag** of that type.

## Tag types

| Tag type | What it labels | Tags |
|---|---|---|
| `product-segment` | The market segment a product belongs to. | Analytics, Compute, Cybersecurity, Database, IAM, ITSM, Storage, … (45 segments) |
| `service-segment` | The kind of service a provider offers. | Backup and Disaster Recovery, Compliance Monitoring, IT, NOC, Penetration Testing, Risk Assessment, SOC, Telco / Communications, Training |
| `query-folder` | The folder a saved query is listed under. | Assets, Cloud Computing, Configuration/Benchmarks Risk, Data Protection / Backups, DevSecOps, Human Resources / Personnel, Implementation Statements, Policies, User Risk, Software Development Lifecycle |
| `env-type` | The environment a resource runs in. | ci, dev, lab, prod, qa, stage, uat |
| `task-type` | The kind of work a task is. | bug, feature, task |
| `marketplace` | Records related to ZeroBias marketplace applications. | demo (demo data hidden from production views), platform_provider (ZeroBias acting as a provider) |
| `other` | Tags that fit no other type. | remote_development (Remote Development Connections) |

## Adding a tag

1. Add `<name>.yml` to the tag type's folder. The file name is the tag's name.
2. Give it a new, unique `id` (a UUID) and a `description`:

   ```yaml
   id: 00000000-0000-0000-0000-000000000000
   description: What the tag means
   ```

3. A new tag type is a new folder. Its optional `index.yml` sets the type's `description` and `color`.

Keep a tag's `id` once it is published: the load updates tags by id.
