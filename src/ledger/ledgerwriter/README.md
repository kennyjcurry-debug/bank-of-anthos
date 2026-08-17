# Ledger Writer Service

The ledger writer service accepts and validates incoming transactions before writing them to the ledger.

Implemented in Java with Spring Boot.

### Endpoints

| Endpoint           | Type  | Auth? | Description                                          |
| ------------------ | ----- | ----- | ---------------------------------------------------- |
| `/ready`           | GET   |       |  Readiness probe endpoint.                           |
| `/transactions`    | POST  | 🔒    |  Submits a transaction to be appended to the ledger. |
| `/version`         | GET   |       |  Returns the contents of `$VERSION`                  |

### Environment Variables

- `VERSION`
  - a version string for the service
- `PORT`
  - the port for the webserver
- `JVM_OPTS`
  - settings for the JVM. Used to obey container memory limits
- `LOG_LEVEL`
  - the service-wide [log level](https://logging.apache.org/log4j/2.x/manual/customloglevels.html) (default: INFO)
- `TRANSFER_REVIEW_THRESHOLD`
  - optional. Transactions at or above this amount, in cents, are held for compliance review
    rather than written to the ledger. This covers every transaction submitted to
    `POST /transactions`, incoming deposits included, not just outgoing transfers. The boundary
    is inclusive: a transaction exactly at the threshold is held. (default: 1000000, i.e.
    $10,000)
  
- ConfigMap `environment-config`:
  - `LOCAL_ROUTING_NUM`
    - the routing number for our bank
  - `PUB_KEY_PATH`
    - the path to the JWT signer's public key, mounted as a secret

- ConfigMap `service-api-config`
  - `BALANCES_API_ADDR`
    - the address and port of the `balancereader` service

- ConfigMap `ledger-db-config`:
  - `SPRING_DATASOURCE_URL`
    - URL of the `ledger-db` service
  - `SPRING_DATASOURCE_USERNAME`
    - username for the `ledger-db` database
  - `SPRING_DATASOURCE_PASSWORD`
    - password for the `ledger-db` database

### Kubernetes Resources

- [deployments/ledgerwriter](/kubernetes-manifests/ledger-writer.yaml)
- [service/ledgerwriter](/kubernetes-manifests/ledger-writer.yaml)
