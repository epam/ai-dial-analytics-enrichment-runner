# Cloud SQL runtime-state access

This doc covers pointing the runner's PostgreSQL runtime-state database at a Cloud SQL for PostgreSQL instance
with IAM database authentication: which variables carry the connection, which identity signs in, and what the
instance's owner has to grant. No real password exists anywhere. The variables themselves are in
[`configuration.md`](configuration.md#postgresql). This doc is the operator side of them.

The runner ships the [Cloud SQL Java Connector](https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory),
a socket factory for the PostgreSQL JDBC driver. No property is dedicated to it. It is selected by its own
parameters in the JDBC URL, and a URL without them never loads it. ADAS ships the same connector for its catalog,
so both services can run on one instance, each with its own database.

## Connection

The datasource URL is composed as
`jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}?${POSTGRES_OPS}`. The connector's
parameters go in `POSTGRES_OPS`:

```bash
POSTGRES_DATABASE=enrichment_runner
POSTGRES_OPS=cloudSqlInstance=<project>:<region>:<instance>&enableIamAuth=true&socketFactory=com.google.cloud.sql.postgres.SocketFactory&sslmode=disable
POSTGRES_DATASOURCE_USERNAME=<name>@<project>.iam
```

- `cloudSqlInstance` is the instance connection name, as `gcloud sql instances describe` prints it. It alone
  names the target, so `POSTGRES_HOST` and `POSTGRES_PORT` are ignored and can keep their defaults.
- `POSTGRES_DATASOURCE_USERNAME` is the IAM database user: the service account's email **without** the
  `.gserviceaccount.com` suffix.
- `sslmode=disable` turns off the driver's own TLS negotiation. The connector already encrypts the
  connection with its ephemeral certificate, so this does not send anything in clear text.
- `POSTGRES_DATASOURCE_PASSWORD` is ignored under IAM authentication but must not be empty: the connector
  documents that an empty value fails driver-level validation. Leave it unset so the `postgres` default
  applies, or set any placeholder.
- Add `&ipTypes=PRIVATE` when the instance has a private IP that the cluster reaches over its VPC. By default the
  connector prefers the public IP when the instance has both.

A JDBC URL kept whole, such as `jdbc:postgresql:///<database>?cloudSqlInstance=...`, can be given instead
through Spring's own `SPRING_DATASOURCE_URL`. It replaces the composed URL, and the `POSTGRES_HOST`, `POSTGRES_PORT`,
`POSTGRES_DATABASE` and `POSTGRES_OPS` parts no longer apply.

## Credentials

With `enableIamAuth=true` the connector signs in as the principal of Application Default Credentials. Cloud SQL
needs that principal to be a **Google service account**, because an IAM database user is a user, a service
account or a group, never a federated principal:

- **Workload Identity (GKE):** annotate the pod's Kubernetes service account with
  `iam.gke.io/gcp-service-account` and grant `roles/iam.workloadIdentityUser` on the Google service account to
  that Kubernetes service account. Granting roles directly to the Kubernetes service account's federated
  principal leaves no database user to sign in as.
- **Mounted key file:** point `GOOGLE_APPLICATION_CREDENTIALS` at a service-account key.
- **Federation from another cloud:** point `GOOGLE_APPLICATION_CREDENTIALS` at a configuration that
  impersonates a service account (it has a `service_account_impersonation_url`). Without impersonation the
  sign-in is refused.

The runner and ADAS may sign in as the same Google service account or as separate ones. Separate accounts keep
each service unable to reach the other's database.

The connector refreshes its token and ephemeral client certificate in the background. Pooled connections
survive a refresh, and no restart is needed.

## IAM and database grants

The service account needs:

- `roles/cloudsql.client` on the project, to fetch the instance's connection settings and an ephemeral
  certificate.
- `roles/cloudsql.instanceUser` on the project, to sign in with IAM.
- An IAM database user on the instance:

  ```bash
  gcloud sql users create <name>@<project>.iam --instance=<instance> --type=cloud_iam_service_account
  ```

- Inside a **fresh** database, the right to connect and to create objects in `public`, where the migrations
  put db-scheduler's `scheduled_tasks` and every runner table. The IAM user then owns each table it creates. On
  PostgreSQL 15 and later, `public` no longer grants `CREATE` to everyone, so grant it explicitly. Run as a user
  holding `cloudsqlsuperuser`:

  ```sql
  GRANT CONNECT, TEMPORARY ON DATABASE enrichment_runner TO "<name>@<project>.iam";
  GRANT USAGE, CREATE ON SCHEMA public TO "<name>@<project>.iam";
  ```

- When an **existing** database switches from a password user to the IAM user, those grants cover none of the
  tables already there: queries fail with `permission denied for table`, and the next migration fails with
  `must be owner of table`, which aborts startup. Hand the old user's objects over first, connected to the
  runner's database as a user that is a member of both roles:

  ```sql
  REASSIGN OWNED BY <old-user> TO "<name>@<project>.iam";
  ```

  Granting the old role to the IAM user (`GRANT <old-user> TO "<name>@<project>.iam";`) also works, and keeps
  the old user's objects where they are.

## Failure

The database is opened at startup when `POSTGRES_MIGRATIONS_ENABLED` is `true`, the default, because the
migrations run then. So any of the following aborts startup:

- no credential available
- a principal that is not an IAM database user
- a missing role
- a wrong instance connection name

With migrations disabled, the same faults surface once the runner first uses the database: the `db` contributor
keeps the readiness probe `DOWN`, and the liveness probe stays `UP`.

## Cloud SQL Auth Proxy

Running the [Cloud SQL Auth Proxy](https://cloud.google.com/sql/docs/postgres/sql-proxy) as a sidecar with
`--auto-iam-authn` works too and needs none of the above parameters. Point `POSTGRES_HOST` at `localhost` and
keep the same IAM database user. The connector does the same job in-process, with no sidecar.
