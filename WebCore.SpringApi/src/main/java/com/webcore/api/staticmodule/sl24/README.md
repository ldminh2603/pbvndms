# SL24 — Customer-specific Limit

This folder contains the dedicated Spring port of the .NET static SL24 module.
It is deliberately separate from `runtime/`, which hosts metadata-driven modules.

```text
sl24/
├── api/          HTTP controllers: /api/static/sl24/*
├── application/  use cases, authorization and transactions
├── model/        request/response contracts shared with React
└── importjob/    Excel upload, preview and execution state
```

The public React contract is retained unchanged:

- `data` — list, detail and CRUD
- `lookup` — SQL/static lookups from `db_limit`
- `action` — limit lifecycle operations
- `import` — Excel upload, preview, execute and T24 sync
