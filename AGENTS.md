# Agent ownership

- Read `doc/README.md`, `doc/architecture.md`, and both contracts before changing a module.
- `workbench-service` changes stay in `workbench-service/` and its module documentation.
- `workbench-node` changes stay in `workbench-node/` and its module documentation.
- `workbench-web` changes stay in `workbench-web/` and its module documentation.
- Module agents must not edit `doc/contracts/`; propose contract changes to the architecture owner.
- Modules must not depend on each other's source, persistence models, generated DTOs, or build outputs.
- Hermes, Kanban, Runtime-specific commands, local absolute paths, raw Runtime events, and full artifacts must not enter the Service domain model.
- Do not merge, deploy, change permissions, write production data, install on a company computer, or delete legacy/external assets without the corresponding explicit Gate.
