<html>
  <div align="center">
    <img src="docs/images/openhouse-logo.jpeg" alt="OpenHouse" width="400" height="300">
  </div>
</html>

Use this guide to deploy OpenHouse services to Kubernetes cluster.

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/)
- [Kubernetes](https://kubernetes.io/docs/setup/)
- [Helm](https://helm.sh/docs/intro/install/)
- [Minikube](https://minikube.sigs.k8s.io/docs/start/)
- [Kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl/)

## Overview

Below we discuss the structure of the Kubernetes and Helm for deploying OpenHouse.

```
infra/recipes/k8s
├── config --> Service specific configuration files (e.g. cluster.yaml)
│   ├── tables --> Configuration files for Catalog service.
│   │   ├── minikube --> Configuration files for minikube cluster
│   │   ├── ... --> Configuration files for custom cluster
│   ├── ... --> Configuration files for other services
│   │   ├── ... --> Similar layout for configuration files for minikube or other cluster
├── templates --> k8s YAML templates such as ConfigMap, Deployment, Service, etc.
│   ├── tables --> k8s spec for tables service: configmap, deployment, service.
│   ├── ... --> k8s spec for other services: configmap, deployment, service.
├── environments --> Environment specific configuration files for overriding default configurations.
│   ├── minikube --> Configuration files for minikube cluster
│   ├── ... --> Configuration files for other clusters
├── helm--> Helm charts for deploying OpenHouse services.
│   ├── tables --> Helm chart for tables service.

```

## Deploying to MiniKube

### Build container images

We build the container images for the services using Docker and make them available to MiniKube. To do this,
run the following command.

```
eval $(minikube docker-env)
```

From the root directory of the repository, run the following command to build the container images.

```
docker build -t openhouse-tables-service:latest -f tables-service.Dockerfile .
docker build -t openhouse-housetables-service:latest -f housetables-service.Dockerfile .
```

### Deploy services

We use Helm to deploy the services to MiniKube. To do this, run the following commands from the [helm](infra/recipes/k8s/helm)
directory.

To deploy the catalog service:
```
helm install openhouse-tables-service tables -f ../environments/minikube/tables/values.yaml
```

To deploy the house table service:
```
helm install openhouse-housetables-service housetables -f ../environments/minikube/housetables/values.yaml
```

In case an existing deployment needs to be updated, use the following command.
```
helm upgrade openhouse-tables-service tables -f ../environments/minikube/tables/values.yaml
helm upgrade openhouse-housetables-service housetables -f ../environments/minikube/housetables/values.yaml
```

Check if the deployment succeeded, by running the following command.
```
helm list
kubectl get deployments
kubectl get pods
```

### Accessing the services

To access Catalog (tables) service we need to open a tunnel. To do this, run the following command.
```
 minikube service openhouse-tables-service
```

URL for the service will be displayed in the terminal. Use this URL to access the service.
```
curl -vv -XGET http://"${url}"/v1/databases/db/tables
```

Make sure you get a HTTP 200 (OK) response. Try out more commands to test the service as described in the [SETUP.md](SETUP.md)

### Default Configurations

The configurations for the services are stored in the [config](infra/recipes/k8s/config) directory.
File [cluster.yaml](infra/recipes/k8s/config/tables/minikube/tables.yaml) contains the configuration used by the
Catalog (tables) service and House Tables service. For minikube environment, we use the following configuration for the
services:

**Authentication and Authorization is disabled**

```yaml
  security:
    token:
      interceptor:
        classname: null
    tables:
      authorization:
        enabled: false
```

**House Table Service uses in-memory database**

```yaml
  housetables:
    base-uri: "http://openhouse-housetables-service:8080"
    database:
      type: "IN_MEMORY"
```

**Local File System is used for storing the data**

```yaml
  storage:
    type: "hadoop"
    uri: "file:///"
    root-path: "/tmp/minikube_openhouse"
```

### Custom Configurations

#### Per-database table-count limit

Set `cluster.tables.max-tables-per-database` in the tables-service `cluster.yaml` to a positive
number to limit persisted live tables in each database. The default is `0`; non-positive values
disable enforcement. Changing this configuration requires restarting or redeploying the tables service.

New-table creation (including staged creation) and restoration throw `TableCountLimitExceededException`
when the database already has at least that many live tables. It maps to HTTP 422 through
`UnprocessableEntityException`. Reads, updates and drops of existing tables are unchanged.
Views, soft-deleted tables and uncommitted stages do not count. A drop frees capacity; staged creation
is checked again when committed. This is best-effort admission control: concurrent creates or restores
can pass the count check together and exceed the ceiling.

Operators can grant a runtime exception with the existing HouseTables table-toggle rules. For example,
the following rule exempts new tables in `my_database`:

```sql
INSERT INTO table_toggle_rule (feature, database_pattern, table_pattern)
VALUES ('bypass_table_count_limit', 'my_database', '*');
```

Use a specific table name instead of `*` for a narrower exception. The check consults HouseTables
when a create or restore reaches the limit; no tables-service restart is needed for rule changes.
Toggle patterns are case-sensitive even though catalog identity and table counts are case-insensitive.
Match the database and table spelling sent by the affected caller; add a rule for each spelling in use.
Verify the toggle reports `ACTIVE` for the database and table IDs in the rejected request.
Users cannot self-exempt by setting `bypass_table_count_limit.enabled` in table properties.
Exempt tables still count toward the database total. Remove the rule to end the exception:

```sql
DELETE FROM table_toggle_rule
WHERE feature = 'bypass_table_count_limit'
  AND database_pattern = 'my_database' AND table_pattern = '*';
```

While the limit is enabled, the tables service reports these metrics; the `database` tag carries the
database ID as sent by the caller:

- `repo_table_count_db_total_count` (gauge, `database`): live tables counted by the instance's latest
  create or restore check in that database.
- `repo_table_count_limit` (gauge): the configured `cluster.tables.max-tables-per-database`.
- `repo_table_count_limit_rejected` (counter, `database`): creates and restores rejected at the limit.
- `repo_table_count_limit_bypassed` (counter, `database`): creates and restores admitted at the limit
  by a bypass rule.

#### Metadata location length limit

A table or view write puts its metadata file on storage first and its HouseTables row second. A
location the HouseTables column (`user_table_row.metadata_location`) cannot hold therefore fails at
the row write, after the metadata file is written, and the caller is told the commit's state is
unknown. The location is the storage root, the database name, the table name, two UUIDs and the
metadata file's name, so long names under a deep storage root reach the column's width.

`cluster.housetables.metadata-location-max-length` in the tables-service `cluster.yaml` lets the
tables service refuse such a write instead, with HTTP 400 and before it writes anything. By default
it is `0`, which checks nothing: every write goes to HouseTables as it always did, and the store
decides. Set it to the column's width to turn the check on:

- `255` for a store built from `services/housetables/ddl/` (the production schema);
- `512` for a store whose tables were created by the HouseTables service's bootstrap `schema.sql`
  (it runs at start-up and creates the tables where they do not exist), which declares
  `VARCHAR(512)`.

```yaml
cluster:
  housetables:
    metadata-location-max-length: 255
```

Every write is checked, commits to existing tables included, so never set it below the column's real
width: a table whose location is already longer could no longer be written. Never set it above the
width either: the row write would then fail after the metadata file is written. A value that is not
positive means no check. Changing it requires restarting or redeploying the tables service.

#### Table name length limit

A rename writes its metadata file before its HouseTables row. A target name the HouseTables column
(`user_table_row.table_id`) cannot hold therefore fails at the row write, after the metadata file is
written. A create's name is at most 128 characters by request validation; only a rename can be
longer.

`cluster.housetables.table-id-max-length` in the tables-service `cluster.yaml` lets the tables
service refuse such a rename instead, with HTTP 400 and before it writes anything. By default it is
`0`, which checks nothing. Set it to turn the check on:

- `128` for a store whose tables were created by the HouseTables service's bootstrap `schema.sql`,
  which declares `user_table_row.table_id` as `VARCHAR(128)`;
- for a store built from `services/housetables/ddl/` (the production schema),
  `user_table_row.table_id` is `VARCHAR(255)` but `soft_deleted_user_table_row.table_id` is
  `VARCHAR(128)`: a table renamed to 129 to 255 characters there is stored, and can then not be
  soft-deleted. `128` keeps every table deletable; `255` refuses only what the row cannot hold.

```yaml
cluster:
  housetables:
    table-id-max-length: 128
```

Never set it above the column's real width. A value that is not positive means no check. Changing it
requires restarting or redeploying the tables service.

#### Iceberg REST scan planning

The tables service's Iceberg REST Catalog routes (`/iceberg`) can plan a client's scans on the
server (`POST .../namespaces/{namespace}/tables/{table}/plan`, with the credentials route `GET
.../tables/{table}/credentials`, which answers an empty list). This is off by default: the two
routes then answer 406, as every route the service does not serve, the config answer does not list
them, and an Iceberg client plans its scans itself.

Turn it on only where every principal that may load a table may also read its data. A plan answers
each planned data file's path, partition values and per-column lower and upper bounds (values from
the data), which the service reads from the table's manifests with its own storage access, and the
route asks only for the privilege a table load asks for (`GET_TABLE_METADATA`). Whether it should
ask for a data-read privilege instead is an open question.

```yaml
cluster:
  tables:
    irc:
      scan-planning:
        enabled: true
        max-file-scan-tasks: 10000  # a larger plan is answered failed
        max-concurrent-plans: 4     # a plan beyond that is answered 503 with Retry-After
        timeout-ms: 60000           # a slower plan is answered failed and interrupted
```

The three bounds default to the values shown and must be positive: with scan planning on, the
service does not start otherwise. Each plan call is audited as a table read, as a table load is.
Changing any of them requires restarting or redeploying the tables service.

#### MySQL for HouseTables Service

To use MySQL, update [cluster.yaml](infra/recipes/k8s/config/housetables/minikube/housetables.yaml). Sample
configuration for MySQL is shown below.
    
```yaml
  housetables:
    base-uri: "http://openhouse-housetables-service:8080"
    database:
      type: "MYSQL"
      url: "jdbc:mysql://mysql:3306/oh_db"
```

MySQL database might need credentials in such cases use k8s secrets. These secrets should be made available to House Table
Service, under the name `openhouse-housetables-service-secrets`. Sample command to create the secrets is
shown below.

```
kubectl create secret generic openhouse-housetables-service-secrets --from-literal=MYSQL_PASSWORD=oh_password --from-literal=MYSQL_USER=oh_user
```

Finally, to make k8s deployment aware of above secrets, update the environment specific configurations in 
[environments](infra/recipes/k8s/environments/minikube) directory accordingly. Sample configuration that is overridden
during `helm install` is shown below.

```yaml
  mysql:
    enabled: true
    secrets:
      HTS_DB_USER: MYSQL_USER
      HTS_DB_PASSWORD: MYSQL_PASSWORD
```

#### MySQL for Optimizer

Point REST, Analyzer, and Scheduler at the same database with Spring Boot's `spring.datasource.url`,
`spring.datasource.username`, and `spring.datasource.password`. Each process reads them from
`OPTIMIZER_DB_URL`, `OPTIMIZER_DB_USER`, and `OPTIMIZER_DB_PASSWORD`; any other Spring Boot
configuration source, such as `--spring.config.additional-location`, works too. Without a URL, the
REST service tries `jdbc:mysql://localhost:3306/oh_db`, and the Analyzer and Scheduler use an
in-memory H2 database.

For certificate authentication, pass
[Connector/J's SSL properties](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-using-ssl.html)
in the URL and mount the referenced files:

```
jdbc:mysql://mysql:3306/oh_db?sslMode=VERIFY_IDENTITY&clientCertificateKeyStoreUrl=file:/path/identity.p12&clientCertificateKeyStorePassword=...&trustCertificateKeyStoreUrl=file:/path/cacerts&trustCertificateKeyStorePassword=...
```

Pool settings use `spring.datasource.hikari.*`.

The REST service runs Flyway migrations at startup. These create and update the tables. Flyway
records each migration in `optimizer_schema_history`. Hibernate then validates the tables against
the entities in the service and in both apps.

The service can share HouseTables' database. Migrations may only change the optimizer tables.
They must not reference HouseTables' tables. The optimizer tables must not exist before the first
rollout. Deploy the service before the Analyzer and Scheduler. Set a startup probe that allows
enough time for the slowest migration.

[Optimizer Schema Migrations](docs/development/optimizer-schema-migrations.md#database-privileges)
describes MySQL grants that limit a user to the optimizer tables. CI runs the migrations under those
grants. Production still uses shared credentials. The CI test does not isolate production. Require
the build. Review the SQL before you deploy.

#### Hadoop Compatible File System for Tables Service

To use remote Hadoop compatible filesystem, update the `storage.uri` in [cluster.yaml](infra/recipes/k8s/config/tables/minikube/tables.yaml)

```yaml
  storage:
    type: "hadoop"
    uri: "hdfs://namenode:9000"
    root-path: "/data/openhouse"
```