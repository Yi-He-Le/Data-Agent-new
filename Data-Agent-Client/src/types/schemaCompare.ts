/** 结构比较的六种状态，与后端 DiffStatus 一致；UNKNOWN 表示元数据不可用、无法判定。 */
export type SchemaDiffStatus =
  | 'ONLY_SOURCE'
  | 'ONLY_TARGET'
  | 'DIFFERENT'
  | 'SAME'
  | 'READ_ERROR'
  | 'UNKNOWN';

/** 参与比较的对象类型，与后端 DiffObjectType 一致。 */
export type SchemaDiffObjectType =
  | 'TABLE'
  | 'COLUMN'
  | 'PRIMARY_KEY'
  | 'INDEX'
  | 'FOREIGN_KEY'
  | 'UNIQUE'
  | 'CHECK'
  | 'COMMENT';

export interface SchemaAttributeDiff {
  name: string;
  sourceValue: string | null;
  targetValue: string | null;
}

export interface SchemaObjectDiff {
  objectType: SchemaDiffObjectType;
  objectKey: string;
  status: SchemaDiffStatus;
  attributes: SchemaAttributeDiff[];
}

export interface SchemaTableDiff {
  tableName: string;
  status: SchemaDiffStatus;
  objects: SchemaObjectDiff[];
  message: string | null;
}

export interface SchemaCompareSummary {
  onlySource: number;
  onlyTarget: number;
  different: number;
  same: number;
  readError: number;
  unknown: number;
}

export interface SchemaCompareResult {
  summary: SchemaCompareSummary;
  tables: SchemaTableDiff[];
  warnings: string[];
}

export interface SchemaCompareParams {
  sourceConnectionId: number;
  targetConnectionId: number;
  sourceCatalog?: string | null;
  sourceSchema?: string | null;
  targetCatalog?: string | null;
  targetSchema?: string | null;
  includeTables?: string[];
  excludeTables?: string[];
  ignoreColumnOrder?: boolean;
  ignoreComments?: boolean;
}
