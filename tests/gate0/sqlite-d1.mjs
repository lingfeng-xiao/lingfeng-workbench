import { DatabaseSync } from "node:sqlite";

class Statement {
  constructor(database, sql, parameters = []) {
    this.database = database;
    this.sql = sql;
    this.parameters = parameters;
  }

  bind(...parameters) {
    return new Statement(this.database, this.sql, parameters);
  }

  run() {
    return this.database.prepare(this.sql).run(...this.parameters);
  }

  execute() {
    return /^\\s*(select|pragma|with)\\b/iu.test(this.sql) ? this.all() : this.run();
  }

  all() {
    return { results: this.database.prepare(this.sql).all(...this.parameters) };
  }

  first() {
    return this.database.prepare(this.sql).get(...this.parameters) || null;
  }
}

export class SqliteD1 {
  constructor() {
    this.database = new DatabaseSync(":memory:");
    this.database.exec("PRAGMA foreign_keys = ON");
  }

  prepare(sql) {
    return new Statement(this.database, sql);
  }

  batch(statements) {
    this.database.exec("BEGIN IMMEDIATE");
    try {
      const results = statements.map((statement) => statement.execute());
      this.database.exec("COMMIT");
      return results;
    } catch (error) {
      this.database.exec("ROLLBACK");
      throw error;
    }
  }

  exec(sql) {
    return this.database.exec(sql);
  }

  close() {
    this.database.close();
  }
}
