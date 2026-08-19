# W0-C contract evidence

This directory records commit-bound evidence for the v0.2 product-contract candidate.

The candidate defines exactly two top-level spaces, fourteen versioned control objects,
append-only user Gate records, a closed artifact classification policy, and frozen v1
navigation/API/permission maps. It does not perform a production migration or write,
publish an object, replay a real approval, merge the Draft PR, or deploy a site.

Safe isolated validation command:

```bash
python -m unittest discover -s tests/product_contracts -v
```

The test store uses an in-memory SQLite database and synthetic records only. Workflow
run URLs and exact results must be added only after cloud execution has completed; this
file deliberately does not claim that a run passed.
