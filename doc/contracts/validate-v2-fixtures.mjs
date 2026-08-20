import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { parse as parseYaml } from "yaml";

const contractDirectory = dirname(fileURLToPath(import.meta.url));
const fixtureDirectory = join(contractDirectory, "fixtures", "v2");
const maximumMessageBytes = 64 * 1024;
const forbiddenKeys = new Set([
  "runtimeSessionId",
  "sessionId",
  "resumeToken",
  "localPath",
  "absolutePath",
  "rawEvent",
  "diff",
  "fullLog",
  "artifact"
]);

const specifications = {
  client: await readYaml("client-api-v2.openapi.yaml"),
  node: await readYaml("node-protocol-v2.openapi.yaml")
};
const manifest = JSON.parse(await readFile(join(fixtureDirectory, "manifest.json"), "utf8"));
const validators = new Map();
const failures = [];

for (const fixture of manifest) {
  const fixturePath = join(fixtureDirectory, fixture.file);
  const encodedFixture = await readFile(fixturePath);
  const payload = JSON.parse(encodedFixture.toString("utf8"));
  const validator = getValidator(fixture.spec, fixture.schema);
  const schemaValid = validator(payload);
  const semanticErrors = validateSemanticBindings(payload);
  const actualValid = schemaValid && semanticErrors.length === 0;

  if (encodedFixture.byteLength > maximumMessageBytes) {
    failures.push(`${fixture.file}: exceeds ${maximumMessageBytes} bytes`);
  }
  if (fixture.valid) {
    const sensitiveKeys = findForbiddenKeys(payload);
    if (sensitiveKeys.length > 0) {
      failures.push(`${fixture.file}: contains forbidden keys ${sensitiveKeys.join(", ")}`);
    }
  }
  if (actualValid !== fixture.valid) {
    const schemaErrors = validator.errors ? JSON.stringify(validator.errors) : "none";
    failures.push(
      `${fixture.file}: expected valid=${fixture.valid}, schemaErrors=${schemaErrors}, semanticErrors=${semanticErrors.join("; ")}`
    );
  }
}

assertContractBoundaries();

if (failures.length > 0) {
  throw new Error(`Contract fixture gate failed:\n${failures.join("\n")}`);
}

console.log(`Validated ${manifest.length} v2 positive/negative fixtures with strict schemas and boundary checks.`);

async function readYaml(fileName) {
  return parseYaml(await readFile(join(contractDirectory, fileName), "utf8"));
}

function getValidator(specificationName, schemaName) {
  const cacheKey = `${specificationName}:${schemaName}`;
  if (validators.has(cacheKey)) {
    return validators.get(cacheKey);
  }
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const specification = structuredClone(specifications[specificationName]);
  const definitions = rewriteReferences(specification.components.schemas);
  const validator = ajv.compile({
    $schema: "https://json-schema.org/draft/2020-12/schema",
    $defs: definitions,
    $ref: `#/$defs/${schemaName}`
  });
  validators.set(cacheKey, validator);
  return validator;
}

function rewriteReferences(value) {
  if (Array.isArray(value)) {
    return value.map(rewriteReferences);
  }
  if (value === null || typeof value !== "object") {
    return value;
  }
  return Object.fromEntries(
    Object.entries(value).map(([key, child]) => {
      if (key === "$ref" && typeof child === "string") {
        return [key, child.replace("#/components/schemas/", "#/$defs/")];
      }
      return [key, rewriteReferences(child)];
    })
  );
}

function validateSemanticBindings(payload) {
  const errors = [];
  if (payload.targetNodeId && payload.nodeId && payload.targetNodeId !== payload.nodeId) {
    errors.push("targetNodeId must match authenticated nodeId");
  }
  if (payload.eventType === "RUN_TERMINAL" && payload.runtimeOutcome === "SUCCEEDED" && payload.acceptanceStatus !== "PASSED") {
    errors.push("SUCCEEDED terminal must not claim completion without PASSED acceptance");
  }
  return errors;
}

function findForbiddenKeys(value, path = "$") {
  if (Array.isArray(value)) {
    return value.flatMap((child, index) => findForbiddenKeys(child, `${path}[${index}]`));
  }
  if (value === null || typeof value !== "object") {
    return [];
  }
  return Object.entries(value).flatMap(([key, child]) => {
    const currentPath = `${path}.${key}`;
    const ownMatch = forbiddenKeys.has(key) ? [currentPath] : [];
    return ownMatch.concat(findForbiddenKeys(child, currentPath));
  });
}

function assertContractBoundaries() {
  for (const [specificationName, specification] of Object.entries(specifications)) {
    for (const [pathName, pathItem] of Object.entries(specification.paths)) {
      for (const [method, operation] of Object.entries(pathItem)) {
        if (!["post", "put", "patch"].includes(method)) {
          continue;
        }
        if (operation.requestBody?.["x-max-body-bytes"] !== maximumMessageBytes) {
          failures.push(`${specificationName} ${method.toUpperCase()} ${pathName}: missing 64 KiB request boundary`);
        }
        if (!operation["x-required-scope"]) {
          failures.push(`${specificationName} ${method.toUpperCase()} ${pathName}: missing explicit scope`);
        }
      }
    }
    const serialized = JSON.stringify(specification);
    for (const forbiddenKey of forbiddenKeys) {
      if (serialized.includes(`\"${forbiddenKey}\"`)) {
        failures.push(`${specificationName}: contract exposes forbidden key ${forbiddenKey}`);
      }
    }
  }
}
