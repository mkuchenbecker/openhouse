<!-- Generator: Widdershins v4.0.1 -->

<h1 id="openhouse-tables-apis">OpenHouse Tables APIs v0.1</h1>

> Scroll down for code samples, example requests and responses. Select a language for code samples from the tabs above or the mobile navigation menu.

API description for OpenHouse Tables API

Base URLs:

* <a href="http://localhost:8000">http://localhost:8000</a>

<a href="http://swagger.io/terms">Terms of service</a>

License: <a href="http://springdoc.org">Apache 2.0</a>

<h1 id="openhouse-tables-apis-table">Table</h1>

## Restore Soft Deleted Table

<a id="opIdrestoreTableV1"></a>

> Code samples

`PUT /v1/databases/{databaseId}/tables/{tableId}/restore`

Restores a soft deleted table identified by tableId, databaseId, and deletedAtMs

<h3 id="restore-soft-deleted-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|
|deletedAtMs|query|integer(int64)|true|Deleted at timestamp in milliseconds|

<h3 id="restore-soft-deleted-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|Soft Deleted Table RESTORE: NO CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Soft Deleted Table RESTORE: BAD REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Soft Deleted Table RESTORE: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Soft Deleted Table RESTORE: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Soft Deleted Table RESTORE: NOT FOUND|None|
|500|[Internal Server Error](https://tools.ietf.org/html/rfc7231#section-6.6.1)|Soft Deleted Table RESTORE: INTERNAL SERVER ERROR|None|

<aside class="success">
This operation does not require authentication
</aside>

## Get Table in a Database

<a id="opIdgetTableV1"></a>

> Code samples

`GET /v1/databases/{databaseId}/tables/{tableId}`

Returns a Table resource identified by tableId in the database identified by databaseId.

<h3 id="get-table-in-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|

> Example responses

> 200 Response

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "tableUri": "my_cluster.my_database.my_table",
  "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
  "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
  "tableVersion": "string",
  "tableCreator": "bob",
  "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
  "lastModifiedTime": 1651002318265,
  "creationTime": 1651002318265,
  "tableProperties": {
    "key": "value"
  },
  "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
  "clustering": [
    "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
  ],
  "policies": {
    "retention": "{retention:{count:3, granularity: 'day'}}",
    "sharingEnabled": false,
    "columnTags": "{'colName': [PII, HC]}",
    "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
    "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
    "lockState": "{lockState: {locked: true/false}"
  },
  "tableType": "PRIMARY_TABLE",
  "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
  "config": {
    "property1": "string",
    "property2": "string"
  }
}
```

<h3 id="get-table-in-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Table GET: OK|[GetTableResponseBody](#schemagettableresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Table GET: UNAUTHORIZED|[GetTableResponseBody](#schemagettableresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Table GET: FORBIDDEN|[GetTableResponseBody](#schemagettableresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Table GET: NOT_FOUND|[GetTableResponseBody](#schemagettableresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Update a Table

<a id="opIdupdateTableV1"></a>

> Code samples

`PUT /v1/databases/{databaseId}/tables/{tableId}`

Updates or creates a Table and returns the Table resources. If the table does not exist, it will be created. If the table exists, it will be updated.

> Body parameter

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":3,\"required\":true,\"name\":\"timestampColumn\",\"type\":\"timestamp\"}]}",
  "newIntermediateSchemas": [
    "{\"type\": \"struct\", \"fields\": [...]}",
    "{...}"
  ],
  "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
  "clustering": "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]",
  "tableProperties": {
    "key": "value"
  },
  "policies": {
    "retention": "{retention:{count:3, granularity: 'day'}}",
    "sharingEnabled": false,
    "columnTags": "{'colName': [PII, HC]}",
    "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
    "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
    "lockState": "{lockState: {locked: true/false}"
  },
  "stageCreate": false,
  "stageReplace": false,
  "replaceCommit": false,
  "baseTableVersion": "string",
  "tableType": "PRIMARY_TABLE",
  "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}"
}
```

<h3 id="update-a-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|
|body|body|[CreateUpdateTableRequestBody](#schemacreateupdatetablerequestbody)|true|none|

> Example responses

> 200 Response

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "tableUri": "my_cluster.my_database.my_table",
  "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
  "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
  "tableVersion": "string",
  "tableCreator": "bob",
  "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
  "lastModifiedTime": 1651002318265,
  "creationTime": 1651002318265,
  "tableProperties": {
    "key": "value"
  },
  "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
  "clustering": [
    "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
  ],
  "policies": {
    "retention": "{retention:{count:3, granularity: 'day'}}",
    "sharingEnabled": false,
    "columnTags": "{'colName': [PII, HC]}",
    "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
    "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
    "lockState": "{lockState: {locked: true/false}"
  },
  "tableType": "PRIMARY_TABLE",
  "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
  "config": {
    "property1": "string",
    "property2": "string"
  }
}
```

<h3 id="update-a-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Table PUT: UPDATED|[GetTableResponseBody](#schemagettableresponsebody)|
|201|[Created](https://tools.ietf.org/html/rfc7231#section-6.3.2)|Table PUT: CREATED|[GetTableResponseBody](#schemagettableresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Table PUT: BAD_REQUEST|[GetTableResponseBody](#schemagettableresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Table PUT: UNAUTHORIZED|[GetTableResponseBody](#schemagettableresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Table PUT: FORBIDDEN|[GetTableResponseBody](#schemagettableresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Table PUT: DB_NOT_FOUND|[GetTableResponseBody](#schemagettableresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## DELETE Table

<a id="opIddeleteTableV1"></a>

> Code samples

`DELETE /v1/databases/{databaseId}/tables/{tableId}`

Deletes a table resource

<h3 id="delete-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|

<h3 id="delete-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|Table DELETE: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Table DELETE: BAD_REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Table DELETE: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Table DELETE: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Table DELETE: TBL_DB_NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

## Paginated Search Tables in a Database

<a id="opIdsearchTablesPaginatedV2"></a>

> Code samples

`POST /v2/databases/{databaseId}/tables/search`

Returns a Page of Table resources present in a database. Only filter supported is 'database_id'.

<h3 id="paginated-search-tables-in-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|page|query|integer(int32)|false|none|
|size|query|integer(int32)|false|none|
|sortBy|query|string|false|none|
|fields|query|array[string]|false|Optional list of GetTableResponseBody field names to populate beyond the identifiers (databaseId, tableId). Requires GET_TABLE_METADATA on the database when set. Supported values: "tableLocation". Accepts comma-separated (?fields=a,b) or repeated (?fields=a&fields=b) values.|

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "tableId": "my_table",
      "databaseId": "my_database",
      "clusterId": "my_cluster",
      "tableUri": "my_cluster.my_database.my_table",
      "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
      "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
      "tableVersion": "string",
      "tableCreator": "bob",
      "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
      "lastModifiedTime": 1651002318265,
      "creationTime": 1651002318265,
      "tableProperties": {
        "key": "value"
      },
      "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
      "clustering": [
        "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
      ],
      "policies": {
        "retention": "{retention:{count:3, granularity: 'day'}}",
        "sharingEnabled": false,
        "columnTags": "{'colName': [PII, HC]}",
        "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
        "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
        "lockState": "{lockState: {locked: true/false}"
      },
      "tableType": "PRIMARY_TABLE",
      "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
      "config": {
        "property1": "string",
        "property2": "string"
      }
    }
  ],
  "pageResults": {
    "totalElements": 0,
    "totalPages": 0,
    "size": 0,
    "content": [
      {
        "tableId": "my_table",
        "databaseId": "my_database",
        "clusterId": "my_cluster",
        "tableUri": "my_cluster.my_database.my_table",
        "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
        "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
        "tableVersion": "string",
        "tableCreator": "bob",
        "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
        "lastModifiedTime": 1651002318265,
        "creationTime": 1651002318265,
        "tableProperties": {
          "key": "value"
        },
        "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
        "clustering": [
          "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
        ],
        "policies": {
          "retention": "{retention:{count:3, granularity: 'day'}}",
          "sharingEnabled": false,
          "columnTags": "{'colName': [PII, HC]}",
          "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
          "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
          "lockState": "{lockState: {locked: true/false}"
        },
        "tableType": "PRIMARY_TABLE",
        "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
        "config": {
          "property1": "string",
          "property2": "string"
        }
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}
```

<h3 id="paginated-search-tables-in-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Table SEARCH: OK|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Table SEARCH: BAD_REQUEST|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Table SEARCH: UNAUTHORIZED|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Table SEARCH: FORBIDDEN|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Table SEARCH: NOT_FOUND|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Create lock on Table

<a id="opIdcreateLockV1"></a>

> Code samples

`POST /v1/databases/{databaseId}/tables/{tableId}/lock`

Create lock on a table identified by databaseId and tableId

> Body parameter

```json
{
  "locked": true,
  "message": "string",
  "creationTime": 1651002318265,
  "expirationInDays": 3
}
```

<h3 id="create-lock-on-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|
|body|body|[CreateUpdateLockRequestBody](#schemacreateupdatelockrequestbody)|true|none|

<h3 id="create-lock-on-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|lock PATCH: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|lock PATCH: BAD_REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|lock PATCH: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|lock PATCH: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|lock PATCH: TABLE_NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

## Delete lock on Table

<a id="opIddeleteLockV1"></a>

> Code samples

`DELETE /v1/databases/{databaseId}/tables/{tableId}/lock`

Delete lock on a table identified by databaseId and tableId

<h3 id="delete-lock-on-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|

<h3 id="delete-lock-on-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|lock PATCH: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|lock PATCH: BAD_REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|lock PATCH: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|lock PATCH: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|lock PATCH: TABLE_NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

## Search Tables in a Database

<a id="opIdsearchTablesV1"></a>

> Code samples

`POST /v1/databases/{databaseId}/tables/search`

Returns a list of Table resources present in a database. Only filter supported is 'database_id'.

<h3 id="search-tables-in-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "tableId": "my_table",
      "databaseId": "my_database",
      "clusterId": "my_cluster",
      "tableUri": "my_cluster.my_database.my_table",
      "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
      "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
      "tableVersion": "string",
      "tableCreator": "bob",
      "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
      "lastModifiedTime": 1651002318265,
      "creationTime": 1651002318265,
      "tableProperties": {
        "key": "value"
      },
      "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
      "clustering": [
        "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
      ],
      "policies": {
        "retention": "{retention:{count:3, granularity: 'day'}}",
        "sharingEnabled": false,
        "columnTags": "{'colName': [PII, HC]}",
        "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
        "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
        "lockState": "{lockState: {locked: true/false}"
      },
      "tableType": "PRIMARY_TABLE",
      "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
      "config": {
        "property1": "string",
        "property2": "string"
      }
    }
  ],
  "pageResults": {
    "totalElements": 0,
    "totalPages": 0,
    "size": 0,
    "content": [
      {
        "tableId": "my_table",
        "databaseId": "my_database",
        "clusterId": "my_cluster",
        "tableUri": "my_cluster.my_database.my_table",
        "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
        "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
        "tableVersion": "string",
        "tableCreator": "bob",
        "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
        "lastModifiedTime": 1651002318265,
        "creationTime": 1651002318265,
        "tableProperties": {
          "key": "value"
        },
        "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
        "clustering": [
          "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
        ],
        "policies": {
          "retention": "{retention:{count:3, granularity: 'day'}}",
          "sharingEnabled": false,
          "columnTags": "{'colName': [PII, HC]}",
          "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
          "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
          "lockState": "{lockState: {locked: true/false}"
        },
        "tableType": "PRIMARY_TABLE",
        "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
        "config": {
          "property1": "string",
          "property2": "string"
        }
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}
```

<h3 id="search-tables-in-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Table SEARCH: OK|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Table SEARCH: BAD_REQUEST|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Table SEARCH: UNAUTHORIZED|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Table SEARCH: FORBIDDEN|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Table SEARCH: NOT_FOUND|[GetAllTablesResponseBody](#schemagetalltablesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Create a Table

<a id="opIdcreateTableV1"></a>

> Code samples

`POST /v1/databases/{databaseId}/tables`

Creates and returns a Table resource in a database identified by databaseId

> Body parameter

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":3,\"required\":true,\"name\":\"timestampColumn\",\"type\":\"timestamp\"}]}",
  "newIntermediateSchemas": [
    "{\"type\": \"struct\", \"fields\": [...]}",
    "{...}"
  ],
  "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
  "clustering": "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]",
  "tableProperties": {
    "key": "value"
  },
  "policies": {
    "retention": "{retention:{count:3, granularity: 'day'}}",
    "sharingEnabled": false,
    "columnTags": "{'colName': [PII, HC]}",
    "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
    "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
    "lockState": "{lockState: {locked: true/false}"
  },
  "stageCreate": false,
  "stageReplace": false,
  "replaceCommit": false,
  "baseTableVersion": "string",
  "tableType": "PRIMARY_TABLE",
  "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}"
}
```

<h3 id="create-a-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|body|body|[CreateUpdateTableRequestBody](#schemacreateupdatetablerequestbody)|true|none|

> Example responses

> 201 Response

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "tableUri": "my_cluster.my_database.my_table",
  "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
  "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
  "tableVersion": "string",
  "tableCreator": "bob",
  "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
  "lastModifiedTime": 1651002318265,
  "creationTime": 1651002318265,
  "tableProperties": {
    "key": "value"
  },
  "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
  "clustering": [
    "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
  ],
  "policies": {
    "retention": "{retention:{count:3, granularity: 'day'}}",
    "sharingEnabled": false,
    "columnTags": "{'colName': [PII, HC]}",
    "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
    "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
    "lockState": "{lockState: {locked: true/false}"
  },
  "tableType": "PRIMARY_TABLE",
  "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
  "config": {
    "property1": "string",
    "property2": "string"
  }
}
```

<h3 id="create-a-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|201|[Created](https://tools.ietf.org/html/rfc7231#section-6.3.2)|Table POST: CREATED|[GetTableResponseBody](#schemagettableresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Table POST: BAD_REQUEST|[GetTableResponseBody](#schemagettableresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Table POST: UNAUTHORIZED|[GetTableResponseBody](#schemagettableresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Table POST: FORBIDDEN|[GetTableResponseBody](#schemagettableresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Table POST: DB_NOT_FOUND|[GetTableResponseBody](#schemagettableresponsebody)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|Table POST: TBL_EXISTS|[GetTableResponseBody](#schemagettableresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## RENAME Table

<a id="opIdrenameTableV1"></a>

> Code samples

`PATCH /v1/databases/{fromDatabaseId}/tables/{fromTableId}/rename`

Renames a table resource

<h3 id="rename-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|fromDatabaseId|path|string|true|Database ID|
|fromTableId|path|string|true|Table ID|
|toDatabaseId|query|string|true|none|
|toTableId|query|string|true|none|

<h3 id="rename-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|Table RENAME: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Table RENAME: BAD_REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Table RENAME: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Table RENAME: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Table RENAME: TBL_DB_NOT_FOUND|None|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|Table RENAME: TBL_EXISTS|None|

<aside class="success">
This operation does not require authentication
</aside>

## Get AclPolicies for Table

<a id="opIdgetAclPoliciesV1"></a>

> Code samples

`GET /v1/databases/{databaseId}/tables/{tableId}/aclPolicies`

Returns principal to role mappings on Table resource identified by databaseId and tableId.

<h3 id="get-aclpolicies-for-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "principal": "string",
      "role": "string",
      "expirationEpochTimeSeconds": 0,
      "properties": {
        "property1": "string",
        "property2": "string"
      }
    }
  ]
}
```

<h3 id="get-aclpolicies-for-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|AclPolicies GET: OK|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|AclPolicies GET: BAD_REQUEST|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|AclPolicies GET: UNAUTHORIZED|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|AclPolicies GET: FORBIDDEN|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|AclPolicies GET: TABLE_NOT_FOUND|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Update AclPolicies for Table

<a id="opIdupdateAclPoliciesV1"></a>

> Code samples

`PATCH /v1/databases/{databaseId}/tables/{tableId}/aclPolicies`

Updates role for principal on Table identified by databaseId and tableId

> Body parameter

```json
{
  "role": "string",
  "principal": "string",
  "operation": "GRANT",
  "expirationEpochTimeSeconds": 1707791165,
  "properties": {
    "property1": "string",
    "property2": "string"
  }
}
```

<h3 id="update-aclpolicies-for-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|
|body|body|[UpdateAclPoliciesRequestBody](#schemaupdateaclpoliciesrequestbody)|true|none|

<h3 id="update-aclpolicies-for-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|AclPolicies PATCH: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|AclPolicies PATCH: BAD_REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|AclPolicies PATCH: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|AclPolicies PATCH: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|AclPolicies PATCH: TABLE_NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

## Search Soft Deleted Tables

<a id="opIdsearchSoftDeletedTablesV1"></a>

> Code samples

`GET /v1/databases/{databaseId}/softDeletedTables`

Returns a paginated list of soft deleted tables in the database identified by databaseId.

<h3 id="search-soft-deleted-tables-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|query|string|false|Table ID to filter by|
|page|query|integer(int32)|false|Page number|
|size|query|integer(int32)|false|Page size|
|sortBy|query|string|false|Sort by field (e.g., 'deletedAtMs', 'tableId', 'purgeAfterMs')|

> Example responses

> 200 Response

<h3 id="search-soft-deleted-tables-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Soft Deleted Tables SEARCH: OK|[GetAllSoftDeletedTablesResponseBody](#schemagetallsoftdeletedtablesresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Soft Deleted Tables SEARCH: BAD REQUEST|[GetAllSoftDeletedTablesResponseBody](#schemagetallsoftdeletedtablesresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Soft Deleted Tables SEARCH: UNAUTHORIZED|[GetAllSoftDeletedTablesResponseBody](#schemagetallsoftdeletedtablesresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Soft Deleted Tables SEARCH: FORBIDDEN|[GetAllSoftDeletedTablesResponseBody](#schemagetallsoftdeletedtablesresponsebody)|
|500|[Internal Server Error](https://tools.ietf.org/html/rfc7231#section-6.6.1)|Soft Deleted Tables SEARCH: INTERNAL SERVER ERROR|[GetAllSoftDeletedTablesResponseBody](#schemagetallsoftdeletedtablesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Get AclPolicies for user principal on a table

<a id="opIdgetAclPoliciesForUserPrincipalV0"></a>

> Code samples

`GET /v0/databases/{databaseId}/tables/{tableId}/aclPolicies/{principal}`

Returns role mappings, access information for a principal on resource identified by databaseId and tableId.

<h3 id="get-aclpolicies-for-user-principal-on-a-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|
|principal|path|string|true|Principal|

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "principal": "string",
      "role": "string",
      "expirationEpochTimeSeconds": 0,
      "properties": {
        "property1": "string",
        "property2": "string"
      }
    }
  ]
}
```

<h3 id="get-aclpolicies-for-user-principal-on-a-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|AclPolicies GET: OK|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|AclPolicies GET: BAD_REQUEST|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|AclPolicies GET: UNAUTHORIZED|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|AclPolicies GET: FORBIDDEN|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|AclPolicies GET: TABLE_NOT_FOUND|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Purge Soft Deleted Table

<a id="opIdpurgeSoftDeletedTableV1"></a>

> Code samples

`DELETE /v1/databases/{databaseId}/tables/{tableId}/purge`

Permanently deletes a soft deleted table identified by tableId in the database identified by databaseId if it was deleted before purgeAfterMs.

<h3 id="purge-soft-deleted-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|
|purgeAfterMs|query|integer(int64)|true|Unix timestamp in milliseconds after which tables should be purged|

<h3 id="purge-soft-deleted-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|Soft Deleted Table PURGE: NO CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Soft Deleted Table PURGE: BAD REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Soft Deleted Table PURGE: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Soft Deleted Table PURGE: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Soft Deleted Table PURGE: NOT FOUND|None|
|500|[Internal Server Error](https://tools.ietf.org/html/rfc7231#section-6.6.1)|Soft Deleted Table PURGE: INTERNAL SERVER ERROR|None|

<aside class="success">
This operation does not require authentication
</aside>

<h1 id="openhouse-tables-apis-snapshot">Snapshot</h1>

## Puts Iceberg snapshots to Table

<a id="opIdputSnapshotsV0"></a>

> Code samples

`PUT /v0/databases/{databaseId}/tables/{tableId}/iceberg/v2/snapshots`

Puts Iceberg snapshots to Table

> Body parameter

```json
{
  "baseTableVersion": "Base table version to apply the change to",
  "jsonSnapshots": [
    "string"
  ],
  "snapshotRefs": {
    "property1": "string",
    "property2": "string"
  },
  "createUpdateTableRequestBody": {
    "tableId": "my_table",
    "databaseId": "my_database",
    "clusterId": "my_cluster",
    "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":3,\"required\":true,\"name\":\"timestampColumn\",\"type\":\"timestamp\"}]}",
    "newIntermediateSchemas": [
      "{\"type\": \"struct\", \"fields\": [...]}",
      "{...}"
    ],
    "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
    "clustering": "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]",
    "tableProperties": {
      "key": "value"
    },
    "policies": {
      "retention": "{retention:{count:3, granularity: 'day'}}",
      "sharingEnabled": false,
      "columnTags": "{'colName': [PII, HC]}",
      "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
      "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
      "lockState": "{lockState: {locked: true/false}"
    },
    "stageCreate": false,
    "stageReplace": false,
    "replaceCommit": false,
    "baseTableVersion": "string",
    "tableType": "PRIMARY_TABLE",
    "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}"
  }
}
```

<h3 id="puts-iceberg-snapshots-to-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|tableId|path|string|true|Table ID|
|body|body|[IcebergSnapshotsRequestBody](#schemaicebergsnapshotsrequestbody)|true|none|

> Example responses

> 200 Response

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "tableUri": "my_cluster.my_database.my_table",
  "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
  "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
  "tableVersion": "string",
  "tableCreator": "bob",
  "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
  "lastModifiedTime": 1651002318265,
  "creationTime": 1651002318265,
  "tableProperties": {
    "key": "value"
  },
  "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
  "clustering": [
    "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
  ],
  "policies": {
    "retention": "{retention:{count:3, granularity: 'day'}}",
    "sharingEnabled": false,
    "columnTags": "{'colName': [PII, HC]}",
    "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
    "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
    "lockState": "{lockState: {locked: true/false}"
  },
  "tableType": "PRIMARY_TABLE",
  "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
  "config": {
    "property1": "string",
    "property2": "string"
  }
}
```

<h3 id="puts-iceberg-snapshots-to-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Iceberg snapshot PUT: UPDATED|[GetTableResponseBody](#schemagettableresponsebody)|
|201|[Created](https://tools.ietf.org/html/rfc7231#section-6.3.2)|Iceberg snapshot PUT: CREATED|[GetTableResponseBody](#schemagettableresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Iceberg snapshot PUT: BAD_REQUEST|[GetTableResponseBody](#schemagettableresponsebody)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|Iceberg snapshot PUT: CONFLICT|[GetTableResponseBody](#schemagettableresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

<h1 id="openhouse-tables-apis-database">Database</h1>

## List all Databases

<a id="opIdgetAllDatabasesV0"></a>

> Code samples

`GET /v0/databases`

Returns a list of Database resources.

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "databaseId": "my_database",
      "clusterId": "my_cluster",
      "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
      "properties": {
        "retention.days": "30"
      }
    }
  ],
  "pageResults": {
    "totalElements": 0,
    "totalPages": 0,
    "size": 0,
    "content": [
      {
        "databaseId": "my_database",
        "clusterId": "my_cluster",
        "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
        "properties": {
          "retention.days": "30"
        }
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}
```

<h3 id="list-all-databases-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Database GET_ALL: OK|[GetAllDatabasesResponseBody](#schemagetalldatabasesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Create a Database

<a id="opIdcreateDatabaseV1"></a>

> Code samples

`POST /v1/databases`

Creates a Database entity identified by databaseId with a server-generated UUID and optional properties. Creating a Database entity does not create or alter any table. Requires the SYSTEM_ADMIN privilege.

> Body parameter

```json
{
  "databaseId": "my_database",
  "properties": {
    "retention.days": "30"
  }
}
```

<h3 id="create-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|body|body|[CreateDatabaseRequestBody](#schemacreatedatabaserequestbody)|true|none|

> Example responses

> 201 Response

```json
{
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
  "properties": {
    "retention.days": "30"
  }
}
```

<h3 id="create-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|201|[Created](https://tools.ietf.org/html/rfc7231#section-6.3.2)|Database POST: CREATED|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Database POST: BAD_REQUEST|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Database POST: UNAUTHORIZED|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Database POST: FORBIDDEN|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|Database POST: DB_EXISTS|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|503|[Service Unavailable](https://tools.ietf.org/html/rfc7231#section-6.6.4)|Database POST: SERVICE_UNAVAILABLE|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Update properties of a Database

<a id="opIdupdateDatabasePropertiesV1"></a>

> Code samples

`POST /v1/databases/{databaseId}/properties`

Applies a sparse update to the properties of the Database entity identified by databaseId: keys in updates are set, keys in removals are removed, and properties not named in the request are not modified. A key present in both updates and removals is rejected. Requires the SYSTEM_ADMIN privilege.

> Body parameter

```json
{
  "updates": {
    "retention.days": "30"
  },
  "removals": [
    "owner"
  ]
}
```

<h3 id="update-properties-of-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|body|body|[UpdateDatabasePropertiesRequestBody](#schemaupdatedatabasepropertiesrequestbody)|true|none|

> Example responses

> 200 Response

```json
{
  "updated": [
    "string"
  ],
  "removed": [
    "string"
  ],
  "missing": [
    "string"
  ]
}
```

<h3 id="update-properties-of-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Database properties POST: UPDATED|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Database properties POST: BAD_REQUEST|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Database properties POST: UNAUTHORIZED|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Database properties POST: FORBIDDEN|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Database properties POST: DB_NOT_FOUND|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|Database properties POST: CONFLICT|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|422|[Unprocessable Entity](https://tools.ietf.org/html/rfc2518#section-10.3)|Database properties POST: UNPROCESSABLE_ENTITY|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|503|[Service Unavailable](https://tools.ietf.org/html/rfc7231#section-6.6.4)|Database properties POST: SERVICE_UNAVAILABLE|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Get AclPolicies on Database

<a id="opIdgetDatabaseAclPolicies"></a>

> Code samples

`GET /databases/{databaseId}/aclPolicies`

Returns principal to role mappings on resource identified by databaseId.

<h3 id="get-aclpolicies-on-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "principal": "string",
      "role": "string",
      "expirationEpochTimeSeconds": 0,
      "properties": {
        "property1": "string",
        "property2": "string"
      }
    }
  ]
}
```

<h3 id="get-aclpolicies-on-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|AclPolicies GET: OK|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|AclPolicies GET: BAD_REQUEST|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|AclPolicies GET: UNAUTHORIZED|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|AclPolicies GET: DB_NOT_FOUND|[GetAclPoliciesResponseBody](#schemagetaclpoliciesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Update AclPolicies on database

<a id="opIdupdateDatabaseAclPoliciesV1"></a>

> Code samples

`PATCH /v1/databases/{databaseId}/aclPolicies`

Updates role for principal on database identified by databaseId

> Body parameter

```json
{
  "role": "string",
  "principal": "string",
  "operation": "GRANT",
  "expirationEpochTimeSeconds": 1707791165,
  "properties": {
    "property1": "string",
    "property2": "string"
  }
}
```

<h3 id="update-aclpolicies-on-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|
|body|body|[UpdateAclPoliciesRequestBody](#schemaupdateaclpoliciesrequestbody)|true|none|

<h3 id="update-aclpolicies-on-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|AclPolicies PATCH: UPDATED|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|AclPolicies PATCH: BAD_REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|AclPolicies PATCH: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|AclPolicies PATCH: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|AclPolicies PATCH: DB_NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

## Paginated list of all Databases

<a id="opIdgetAllDatabasesPaginatedV2"></a>

> Code samples

`GET /v2/databases`

Returns a Page of Database resources.

<h3 id="paginated-list-of-all-databases-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|page|query|integer(int32)|false|none|
|size|query|integer(int32)|false|none|
|sortBy|query|string|false|none|

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "databaseId": "my_database",
      "clusterId": "my_cluster",
      "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
      "properties": {
        "retention.days": "30"
      }
    }
  ],
  "pageResults": {
    "totalElements": 0,
    "totalPages": 0,
    "size": 0,
    "content": [
      {
        "databaseId": "my_database",
        "clusterId": "my_cluster",
        "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
        "properties": {
          "retention.days": "30"
        }
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}
```

<h3 id="paginated-list-of-all-databases-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Database GET_ALL: OK|[GetAllDatabasesResponseBody](#schemagetalldatabasesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Get a Database

<a id="opIdgetDatabaseV1"></a>

> Code samples

`GET /v1/databases/{databaseId}`

Returns the Database entity identified by databaseId, matched ignoring case, with its UUID and properties. A database that only exists as table metadata and has no Database entity is NOT FOUND.

<h3 id="get-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|

> Example responses

> 200 Response

```json
{
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
  "properties": {
    "retention.days": "30"
  }
}
```

<h3 id="get-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Database GET: OK|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Database GET: BAD_REQUEST|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Database GET: UNAUTHORIZED|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Database GET: DB_NOT_FOUND|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|
|503|[Service Unavailable](https://tools.ietf.org/html/rfc7231#section-6.6.4)|Database GET: SERVICE_UNAVAILABLE|[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

## Delete a Database

<a id="opIddeleteDatabaseV1"></a>

> Code samples

`DELETE /v1/databases/{databaseId}`

Deletes the Database entity identified by databaseId. Tables in the database are left untouched and the database is not required to be empty. Requires the SYSTEM_ADMIN privilege.

<h3 id="delete-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|path|string|true|Database ID|

<h3 id="delete-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|Database DELETE: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Database DELETE: BAD_REQUEST|None|
|401|[Unauthorized](https://tools.ietf.org/html/rfc7235#section-3.1)|Database DELETE: UNAUTHORIZED|None|
|403|[Forbidden](https://tools.ietf.org/html/rfc7231#section-6.5.3)|Database DELETE: FORBIDDEN|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Database DELETE: DB_NOT_FOUND|None|
|503|[Service Unavailable](https://tools.ietf.org/html/rfc7231#section-6.6.4)|Database DELETE: SERVICE_UNAVAILABLE|None|

<aside class="success">
This operation does not require authentication
</aside>

# Schemas

<h2 id="tocS_ClusteringColumn">ClusteringColumn</h2>
<!-- backwards compatibility -->
<a id="schemaclusteringcolumn"></a>
<a id="schema_ClusteringColumn"></a>
<a id="tocSclusteringcolumn"></a>
<a id="tocsclusteringcolumn"></a>

```json
"\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"

```

Clustering columns for the table

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|columnName|string|true|none|Name of the clustering column in provided schema. The column should be of the type 'String'.Nested columns can also be provided with a dot-separated name (for example: 'eventHeader.countryCode').Column name is case-sensitive.|
|transform|[Transform](#schematransform)|false|none|Transform of the clustering column. This field is optional.|

<h2 id="tocS_CreateUpdateTableRequestBody">CreateUpdateTableRequestBody</h2>
<!-- backwards compatibility -->
<a id="schemacreateupdatetablerequestbody"></a>
<a id="schema_CreateUpdateTableRequestBody"></a>
<a id="tocScreateupdatetablerequestbody"></a>
<a id="tocscreateupdatetablerequestbody"></a>

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":3,\"required\":true,\"name\":\"timestampColumn\",\"type\":\"timestamp\"}]}",
  "newIntermediateSchemas": [
    "{\"type\": \"struct\", \"fields\": [...]}",
    "{...}"
  ],
  "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
  "clustering": "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]",
  "tableProperties": {
    "key": "value"
  },
  "policies": {
    "retention": "{retention:{count:3, granularity: 'day'}}",
    "sharingEnabled": false,
    "columnTags": "{'colName': [PII, HC]}",
    "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
    "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
    "lockState": "{lockState: {locked: true/false}"
  },
  "stageCreate": false,
  "stageReplace": false,
  "replaceCommit": false,
  "baseTableVersion": "string",
  "tableType": "PRIMARY_TABLE",
  "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}"
}

```

Request containing details of the Table to be created

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|tableId|string|true|none|Unique Resource identifier for a table within a Database|
|databaseId|string|true|none|Unique Resource identifier for the Database containing the Table|
|clusterId|string|true|none|Unique Resource identifier for the Cluster containing the Database|
|schema|string|true|none|Schema of the table. OpenHouse tables use Iceberg schema specification|
|newIntermediateSchemas|[string]¦null|false|none|List of schema JSON strings for intermediate schemas created during this operation. This is used to preserve schema history when multiple schema updates occur in a single commit.|
|timePartitioning|[TimePartitionSpec](#schematimepartitionspec)|false|none|Time partitioning of the table|
|clustering|[[ClusteringColumn](#schemaclusteringcolumn)]¦null|false|none|Clustering columns for the table|
|tableProperties|object|true|none|Table properties|
|» **additionalProperties**|string|false|none|Table properties|
|policies|[Policies](#schemapolicies)|false|none|Policies of the table|
|stageCreate|boolean|false|none|Boolean that determines creating a staged table|
|stageReplace|boolean|false|none|Boolean that determines replacing an existing table in a staged manner. When true, schema and partition evolution checks are bypassed.|
|replaceCommit|boolean|false|none|Boolean that determines committing a previously staged replace. When true, the table is saved as a new table without requiring INITIAL_TABLE_VERSION.|
|baseTableVersion|string|true|none|The version of table that the current update is based upon|
|tableType|string|false|none|The type of a table|
|sortOrder|string¦null|false|none|The sort order of a table|

#### Enumerated Values

|Property|Value|
|---|---|
|tableType|PRIMARY_TABLE|
|tableType|REPLICA_TABLE|

<h2 id="tocS_History">History</h2>
<!-- backwards compatibility -->
<a id="schemahistory"></a>
<a id="schema_History"></a>
<a id="tocShistory"></a>
<a id="tocshistory"></a>

```json
"{history:{maxAge:3, granularity: 'day', versions: 5}}"

```

History as required in /tables API request. This field holds the snapshot retention specification.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|maxAge|integer(int32)|false|none|Time period in count <granularity> to keep the snapshot history on the table|
|granularity|string|false|none|time period granularity for the snapshot history|
|versions|integer(int32)|false|none|Number of snapshots to keep within history for the table after snapshot expiration|

#### Enumerated Values

|Property|Value|
|---|---|
|granularity|HOUR|
|granularity|DAY|
|granularity|MONTH|
|granularity|YEAR|

<h2 id="tocS_IcebergSnapshotsRequestBody">IcebergSnapshotsRequestBody</h2>
<!-- backwards compatibility -->
<a id="schemaicebergsnapshotsrequestbody"></a>
<a id="schema_IcebergSnapshotsRequestBody"></a>
<a id="tocSicebergsnapshotsrequestbody"></a>
<a id="tocsicebergsnapshotsrequestbody"></a>

```json
{
  "baseTableVersion": "Base table version to apply the change to",
  "jsonSnapshots": [
    "string"
  ],
  "snapshotRefs": {
    "property1": "string",
    "property2": "string"
  },
  "createUpdateTableRequestBody": {
    "tableId": "my_table",
    "databaseId": "my_database",
    "clusterId": "my_cluster",
    "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":3,\"required\":true,\"name\":\"timestampColumn\",\"type\":\"timestamp\"}]}",
    "newIntermediateSchemas": [
      "{\"type\": \"struct\", \"fields\": [...]}",
      "{...}"
    ],
    "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
    "clustering": "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]",
    "tableProperties": {
      "key": "value"
    },
    "policies": {
      "retention": "{retention:{count:3, granularity: 'day'}}",
      "sharingEnabled": false,
      "columnTags": "{'colName': [PII, HC]}",
      "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
      "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
      "lockState": "{lockState: {locked: true/false}"
    },
    "stageCreate": false,
    "stageReplace": false,
    "replaceCommit": false,
    "baseTableVersion": "string",
    "tableType": "PRIMARY_TABLE",
    "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}"
  }
}

```

Request containing a list of JSON serialized Iceberg Snapshots to be put

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|baseTableVersion|string|true|none|Base Table Version|
|jsonSnapshots|[string]|false|none|List of json serialized snapshots to put|
|snapshotRefs|object|false|none|Map of branch name to json serialized SnapshotRef. Key is the branch name, and value is the SnapshotRef.|
|» **additionalProperties**|string|false|none|Map of branch name to json serialized SnapshotRef. Key is the branch name, and value is the SnapshotRef.|
|createUpdateTableRequestBody|[CreateUpdateTableRequestBody](#schemacreateupdatetablerequestbody)|false|none|Request containing details of the Table to be created|

<h2 id="tocS_LockState">LockState</h2>
<!-- backwards compatibility -->
<a id="schemalockstate"></a>
<a id="schema_LockState"></a>
<a id="tocSlockstate"></a>
<a id="tocslockstate"></a>

```json
"{lockState: {locked: true/false}"

```

Locking state of table.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|locked|boolean|false|none|locked state of a table, example = false, true|
|message|string|false|none|message for locking|
|creationTime|integer(int64)|false|none|lock creation epoch time measured in UTC milliseconds for a table|
|expirationInDays|integer(int32)|false|none|lock expiration time for a table is `n` days from creationTime|

<h2 id="tocS_Policies">Policies</h2>
<!-- backwards compatibility -->
<a id="schemapolicies"></a>
<a id="schema_Policies"></a>
<a id="tocSpolicies"></a>
<a id="tocspolicies"></a>

```json
{
  "retention": "{retention:{count:3, granularity: 'day'}}",
  "sharingEnabled": false,
  "columnTags": "{'colName': [PII, HC]}",
  "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
  "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
  "lockState": "{lockState: {locked: true/false}"
}

```

Policies of the table

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|retention|[Retention](#schemaretention)|false|none|Retention as required in /tables API request. The column holds the retention part or Policies.|
|sharingEnabled|boolean|false|none|Whether data sharing needs to enabled for the table in /tables API request. Sharing is disabled by default|
|columnTags|object|false|none|Policy tags applied to columns in /tables API request.|
|» **additionalProperties**|[PolicyTag](#schemapolicytag)|false|none|Policy tags applied to columns in /tables API request.|
|replication|[Replication](#schemareplication)|false|none|Replication as required in /tables API request. This field holds the replication spec config.|
|history|[History](#schemahistory)|false|none|History as required in /tables API request. This field holds the snapshot retention specification.|
|lockState|[LockState](#schemalockstate)|false|none|Locking state of table.|

<h2 id="tocS_PolicyTag">PolicyTag</h2>
<!-- backwards compatibility -->
<a id="schemapolicytag"></a>
<a id="schema_PolicyTag"></a>
<a id="tocSpolicytag"></a>
<a id="tocspolicytag"></a>

```json
"{'colName': [PII, HC]}"

```

Policy tags applied to columns in /tables API request.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|tags|[string]|false|none|Policy tags|

<h2 id="tocS_Replication">Replication</h2>
<!-- backwards compatibility -->
<a id="schemareplication"></a>
<a id="schema_Replication"></a>
<a id="tocSreplication"></a>
<a id="tocsreplication"></a>

```json
"{replication:{config:[{destination: clusterA, interval: 12H}]}}"

```

Replication as required in /tables API request. This field holds the replication spec config.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|config|[[ReplicationConfig](#schemareplicationconfig)]|true|none|Replication config for the destination cluster name and replication job interval|

<h2 id="tocS_ReplicationConfig">ReplicationConfig</h2>
<!-- backwards compatibility -->
<a id="schemareplicationconfig"></a>
<a id="schema_ReplicationConfig"></a>
<a id="tocSreplicationconfig"></a>
<a id="tocsreplicationconfig"></a>

```json
"[{destination: clusterA, interval: 12H}, {destination: clusterB, interval: 12H}]"

```

Replication config for the destination cluster name and replication job interval

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|destination|string|true|none|Replication destination cluster name|
|interval|string|false|none|Optional parameter interval at which the replication job should run. Default value is 1D|
|cronSchedule|string|false|none|Cron schedule generated from the interval.|

<h2 id="tocS_Retention">Retention</h2>
<!-- backwards compatibility -->
<a id="schemaretention"></a>
<a id="schema_Retention"></a>
<a id="tocSretention"></a>
<a id="tocsretention"></a>

```json
"{retention:{count:3, granularity: 'day'}}"

```

Retention as required in /tables API request. The column holds the retention part or Policies.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|count|integer(int32)|true|none|time period in count <granularity> for which the retention on table will be applied|
|granularity|string|true|none|time period granularity for which the retention on table will be applied|
|columnPattern|[RetentionColumnPattern](#schemaretentioncolumnpattern)|false|none|Optional object to specify retention column in case where timestamp is represented as a string|

#### Enumerated Values

|Property|Value|
|---|---|
|granularity|HOUR|
|granularity|DAY|
|granularity|MONTH|
|granularity|YEAR|

<h2 id="tocS_RetentionColumnPattern">RetentionColumnPattern</h2>
<!-- backwards compatibility -->
<a id="schemaretentioncolumnpattern"></a>
<a id="schema_RetentionColumnPattern"></a>
<a id="tocSretentioncolumnpattern"></a>
<a id="tocsretentioncolumnpattern"></a>

```json
"{columnName:datepartition, pattern: yyyy-MM-dd-HH}"

```

Optional object to specify retention column in case where timestamp is represented as a string

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|columnName|string|true|none|Name of retention column|
|pattern|string|true|none|Pattern for the value of the retention column following java.time.format.DateTimeFormatter standard. Defaults to 'yyyy-MM-dd' day format if empty.|

<h2 id="tocS_TimePartitionSpec">TimePartitionSpec</h2>
<!-- backwards compatibility -->
<a id="schematimepartitionspec"></a>
<a id="schema_TimePartitionSpec"></a>
<a id="tocStimepartitionspec"></a>
<a id="tocstimepartitionspec"></a>

```json
"\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}"

```

Time partitioning of the table

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|columnName|string|true|none|Name of the timestamp column in provided schema. The column should be of the type 'Timestamp'. Nested columns can also be provided with a dot-separated name (for example: 'eventHeader.timeColumn').Column name is case-sensitive.|
|granularity|string|true|none|Granularity of the time partition.|

#### Enumerated Values

|Property|Value|
|---|---|
|granularity|HOUR|
|granularity|DAY|
|granularity|MONTH|
|granularity|YEAR|

<h2 id="tocS_Transform">Transform</h2>
<!-- backwards compatibility -->
<a id="schematransform"></a>
<a id="schema_Transform"></a>
<a id="tocStransform"></a>
<a id="tocstransform"></a>

```json
"{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}"

```

Transform of the clustering column. This field is optional.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|transformType|string|true|none|Type of the transform|
|transformParams|[string]¦null|false|none|Parameters of the transform. This field can be null|

#### Enumerated Values

|Property|Value|
|---|---|
|transformType|TRUNCATE|
|transformType|BUCKET|

<h2 id="tocS_GetTableResponseBody">GetTableResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemagettableresponsebody"></a>
<a id="schema_GetTableResponseBody"></a>
<a id="tocSgettableresponsebody"></a>
<a id="tocsgettableresponsebody"></a>

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "tableUri": "my_cluster.my_database.my_table",
  "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
  "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
  "tableVersion": "string",
  "tableCreator": "bob",
  "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
  "lastModifiedTime": 1651002318265,
  "creationTime": 1651002318265,
  "tableProperties": {
    "key": "value"
  },
  "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
  "clustering": [
    "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
  ],
  "policies": {
    "retention": "{retention:{count:3, granularity: 'day'}}",
    "sharingEnabled": false,
    "columnTags": "{'colName': [PII, HC]}",
    "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
    "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
    "lockState": "{lockState: {locked: true/false}"
  },
  "tableType": "PRIMARY_TABLE",
  "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
  "config": {
    "property1": "string",
    "property2": "string"
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|tableId|string|false|read-only|Unique Resource identifier for a table within a Database|
|databaseId|string|false|read-only|Unique Resource identifier for the Database containing the Table|
|clusterId|string|false|read-only|Unique Resource identifier for the Cluster containing the Database|
|tableUri|string|false|read-only|Fully Qualified Resource URI for the table|
|tableUUID|string|false|read-only|Table UUID|
|tableLocation|string|false|read-only|Location of Table in File System / Blob Store|
|tableVersion|string|false|read-only|Current Version of the Table.|
|tableCreator|string|false|read-only|Authenticated user principal that created the Table.|
|schema|string|false|read-only|Schema of the Table in Iceberg|
|lastModifiedTime|integer(int64)|false|read-only|Last modification epoch time in UTC measured in milliseconds of a table.|
|creationTime|integer(int64)|false|read-only|Table creation epoch time measured in UTC in milliseconds of a table.|
|tableProperties|object|false|read-only|A map of table properties|
|» **additionalProperties**|string|false|none|A map of table properties|
|timePartitioning|[TimePartitionSpec](#schematimepartitionspec)|false|none|Time partitioning of the table|
|clustering|[[ClusteringColumn](#schemaclusteringcolumn)]¦null|false|none|Clustering columns for the table|
|policies|[Policies](#schemapolicies)|false|none|Policies of the table|
|tableType|string|false|read-only|The type of a table|
|sortOrder|string¦null|false|read-only|The sort order of a table|
|config|object¦null|false|read-only|Server-stamped, per-table client configuration overrides, following the Iceberg REST `LoadTableResponse.config` convention: a string map the server controls at runtime without a client re-roll. READ_ONLY and advisory — absent/empty means today's behavior; clients ignore keys they do not understand. Keys are vendor-namespaced (e.g. `openhouse.read-bridge`). Distinct from the table-governance `policies` object.|
|» **additionalProperties**|string¦null|false|none|Server-stamped, per-table client configuration overrides, following the Iceberg REST `LoadTableResponse.config` convention: a string map the server controls at runtime without a client re-roll. READ_ONLY and advisory — absent/empty means today's behavior; clients ignore keys they do not understand. Keys are vendor-namespaced (e.g. `openhouse.read-bridge`). Distinct from the table-governance `policies` object.|

#### Enumerated Values

|Property|Value|
|---|---|
|tableType|PRIMARY_TABLE|
|tableType|REPLICA_TABLE|

<h2 id="tocS_GetAllTablesResponseBody">GetAllTablesResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemagetalltablesresponsebody"></a>
<a id="schema_GetAllTablesResponseBody"></a>
<a id="tocSgetalltablesresponsebody"></a>
<a id="tocsgetalltablesresponsebody"></a>

```json
{
  "results": [
    {
      "tableId": "my_table",
      "databaseId": "my_database",
      "clusterId": "my_cluster",
      "tableUri": "my_cluster.my_database.my_table",
      "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
      "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
      "tableVersion": "string",
      "tableCreator": "bob",
      "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
      "lastModifiedTime": 1651002318265,
      "creationTime": 1651002318265,
      "tableProperties": {
        "key": "value"
      },
      "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
      "clustering": [
        "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
      ],
      "policies": {
        "retention": "{retention:{count:3, granularity: 'day'}}",
        "sharingEnabled": false,
        "columnTags": "{'colName': [PII, HC]}",
        "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
        "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
        "lockState": "{lockState: {locked: true/false}"
      },
      "tableType": "PRIMARY_TABLE",
      "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
      "config": {
        "property1": "string",
        "property2": "string"
      }
    }
  ],
  "pageResults": {
    "totalElements": 0,
    "totalPages": 0,
    "size": 0,
    "content": [
      {
        "tableId": "my_table",
        "databaseId": "my_database",
        "clusterId": "my_cluster",
        "tableUri": "my_cluster.my_database.my_table",
        "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
        "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
        "tableVersion": "string",
        "tableCreator": "bob",
        "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
        "lastModifiedTime": 1651002318265,
        "creationTime": 1651002318265,
        "tableProperties": {
          "key": "value"
        },
        "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
        "clustering": [
          "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
        ],
        "policies": {
          "retention": "{retention:{count:3, granularity: 'day'}}",
          "sharingEnabled": false,
          "columnTags": "{'colName': [PII, HC]}",
          "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
          "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
          "lockState": "{lockState: {locked: true/false}"
        },
        "tableType": "PRIMARY_TABLE",
        "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
        "config": {
          "property1": "string",
          "property2": "string"
        }
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|results|[[GetTableResponseBody](#schemagettableresponsebody)]|false|read-only|List of Table objects in a database|
|pageResults|[PageGetTableResponseBody](#schemapagegettableresponsebody)|false|none|Page of Table objects in a database|

<h2 id="tocS_PageGetTableResponseBody">PageGetTableResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemapagegettableresponsebody"></a>
<a id="schema_PageGetTableResponseBody"></a>
<a id="tocSpagegettableresponsebody"></a>
<a id="tocspagegettableresponsebody"></a>

```json
{
  "totalElements": 0,
  "totalPages": 0,
  "size": 0,
  "content": [
    {
      "tableId": "my_table",
      "databaseId": "my_database",
      "clusterId": "my_cluster",
      "tableUri": "my_cluster.my_database.my_table",
      "tableUUID": "73ea0d21-3c89-4987-a6cf-26e4f86bdcee",
      "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
      "tableVersion": "string",
      "tableCreator": "bob",
      "schema": "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},{\"id\":2,\"required\":true,\"name\":\"name\",\"type\":\"string\"}]}",
      "lastModifiedTime": 1651002318265,
      "creationTime": 1651002318265,
      "tableProperties": {
        "key": "value"
      },
      "timePartitioning": "\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}",
      "clustering": [
        "\"clustering\":[{\"columnName\":\"country\"},{\"columnName\":\"city\",\"transform\":{\"transformType\":\"TRUNCATE\",\"transformParams\":[\"1000\"]}}]"
      ],
      "policies": {
        "retention": "{retention:{count:3, granularity: 'day'}}",
        "sharingEnabled": false,
        "columnTags": "{'colName': [PII, HC]}",
        "replication": "{replication:{config:[{destination: clusterA, interval: 12H}]}}",
        "history": "{history:{maxAge:3, granularity: 'day', versions: 5}}",
        "lockState": "{lockState: {locked: true/false}"
      },
      "tableType": "PRIMARY_TABLE",
      "sortOrder": "\"sortOrder\":{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}",
      "config": {
        "property1": "string",
        "property2": "string"
      }
    }
  ],
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": true,
    "unsorted": true
  },
  "numberOfElements": 0,
  "pageable": {
    "offset": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "pageNumber": 0,
    "pageSize": 0,
    "paged": true,
    "unpaged": true
  },
  "first": true,
  "last": true,
  "empty": true
}

```

Page of Table objects in a database

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|totalElements|integer(int64)|false|none|none|
|totalPages|integer(int32)|false|none|none|
|size|integer(int32)|false|none|none|
|content|[[GetTableResponseBody](#schemagettableresponsebody)]|false|none|none|
|number|integer(int32)|false|none|none|
|sort|[SortObject](#schemasortobject)|false|none|none|
|numberOfElements|integer(int32)|false|none|none|
|pageable|[PageableObject](#schemapageableobject)|false|none|none|
|first|boolean|false|none|none|
|last|boolean|false|none|none|
|empty|boolean|false|none|none|

<h2 id="tocS_PageableObject">PageableObject</h2>
<!-- backwards compatibility -->
<a id="schemapageableobject"></a>
<a id="schema_PageableObject"></a>
<a id="tocSpageableobject"></a>
<a id="tocspageableobject"></a>

```json
{
  "offset": 0,
  "sort": {
    "empty": true,
    "sorted": true,
    "unsorted": true
  },
  "pageNumber": 0,
  "pageSize": 0,
  "paged": true,
  "unpaged": true
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|offset|integer(int64)|false|none|none|
|sort|[SortObject](#schemasortobject)|false|none|none|
|pageNumber|integer(int32)|false|none|none|
|pageSize|integer(int32)|false|none|none|
|paged|boolean|false|none|none|
|unpaged|boolean|false|none|none|

<h2 id="tocS_SortObject">SortObject</h2>
<!-- backwards compatibility -->
<a id="schemasortobject"></a>
<a id="schema_SortObject"></a>
<a id="tocSsortobject"></a>
<a id="tocssortobject"></a>

```json
{
  "empty": true,
  "sorted": true,
  "unsorted": true
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|empty|boolean|false|none|none|
|sorted|boolean|false|none|none|
|unsorted|boolean|false|none|none|

<h2 id="tocS_CreateDatabaseRequestBody">CreateDatabaseRequestBody</h2>
<!-- backwards compatibility -->
<a id="schemacreatedatabaserequestbody"></a>
<a id="schema_CreateDatabaseRequestBody"></a>
<a id="tocScreatedatabaserequestbody"></a>
<a id="tocscreatedatabaserequestbody"></a>

```json
{
  "databaseId": "my_database",
  "properties": {
    "retention.days": "30"
  }
}

```

Request containing the name and optional properties of the Database

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|databaseId|string|true|none|Unique Resource identifier for the Database|
|properties|object|false|none|Optional key-value properties of the database|
|» **additionalProperties**|string|false|none|Optional key-value properties of the database|

<h2 id="tocS_GetDatabaseResponseBody">GetDatabaseResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemagetdatabaseresponsebody"></a>
<a id="schema_GetDatabaseResponseBody"></a>
<a id="tocSgetdatabaseresponsebody"></a>
<a id="tocsgetdatabaseresponsebody"></a>

```json
{
  "databaseId": "my_database",
  "clusterId": "my_cluster",
  "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
  "properties": {
    "retention.days": "30"
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|databaseId|string|false|read-only|Unique Resource identifier for the Database|
|clusterId|string|false|read-only|Unique Resource identifier for the Cluster containing the Database|
|databaseUUID|string|false|read-only|Server-generated UUID that is the canonical identity of the database entity. Absent for databases listed from table metadata that have no database entity.|
|properties|object|false|read-only|Key-value properties of the database entity. Absent for databases listed from table metadata that have no database entity.|
|» **additionalProperties**|string|false|read-only|Key-value properties of the database entity. Absent for databases listed from table metadata that have no database entity.|

<h2 id="tocS_CreateUpdateLockRequestBody">CreateUpdateLockRequestBody</h2>
<!-- backwards compatibility -->
<a id="schemacreateupdatelockrequestbody"></a>
<a id="schema_CreateUpdateLockRequestBody"></a>
<a id="tocScreateupdatelockrequestbody"></a>
<a id="tocscreateupdatelockrequestbody"></a>

```json
{
  "locked": true,
  "message": "string",
  "creationTime": 1651002318265,
  "expirationInDays": 3
}

```

Request containing locked state of the Table to be created/updated

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|locked|boolean|true|none|locked state to be created/updated for table.|
|message|string|false|none|reason for creating/updating the lock on table|
|creationTime|integer(int64)|false|none|lock creation epoch time measured in UTC milliseconds for a table|
|expirationInDays|integer(int32)|false|none|lock expiration time for a table is `n` days from creationTime|

<h2 id="tocS_UpdateDatabasePropertiesRequestBody">UpdateDatabasePropertiesRequestBody</h2>
<!-- backwards compatibility -->
<a id="schemaupdatedatabasepropertiesrequestbody"></a>
<a id="schema_UpdateDatabasePropertiesRequestBody"></a>
<a id="tocSupdatedatabasepropertiesrequestbody"></a>
<a id="tocsupdatedatabasepropertiesrequestbody"></a>

```json
{
  "updates": {
    "retention.days": "30"
  },
  "removals": [
    "owner"
  ]
}

```

Property keys to set and property keys to remove

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|updates|object|false|none|Properties to set on the database. Existing keys are overwritten.|
|» **additionalProperties**|string|false|none|Properties to set on the database. Existing keys are overwritten.|
|removals|[string]|false|none|Property keys to remove from the database.|

<h2 id="tocS_UpdateDatabasePropertiesResponseBody">UpdateDatabasePropertiesResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemaupdatedatabasepropertiesresponsebody"></a>
<a id="schema_UpdateDatabasePropertiesResponseBody"></a>
<a id="tocSupdatedatabasepropertiesresponsebody"></a>
<a id="tocsupdatedatabasepropertiesresponsebody"></a>

```json
{
  "updated": [
    "string"
  ],
  "removed": [
    "string"
  ],
  "missing": [
    "string"
  ]
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|updated|[string]|true|read-only|Property keys that were set.|
|removed|[string]|true|read-only|Property keys that were removed.|
|missing|[string]|false|read-only|Property keys requested for removal that were not present on the database.|

<h2 id="tocS_UpdateAclPoliciesRequestBody">UpdateAclPoliciesRequestBody</h2>
<!-- backwards compatibility -->
<a id="schemaupdateaclpoliciesrequestbody"></a>
<a id="schema_UpdateAclPoliciesRequestBody"></a>
<a id="tocSupdateaclpoliciesrequestbody"></a>
<a id="tocsupdateaclpoliciesrequestbody"></a>

```json
{
  "role": "string",
  "principal": "string",
  "operation": "GRANT",
  "expirationEpochTimeSeconds": 1707791165,
  "properties": {
    "property1": "string",
    "property2": "string"
  }
}

```

Request containing aclPolicies of the Database to be updated

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|role|string|true|none|Role that is being granted/revoked.|
|principal|string|true|none|Grantee principal whose role is being updated|
|operation|string|true|none|Whether this is a grant/revoke request|
|expirationEpochTimeSeconds|integer(int64)|false|none|Optional epoch time in seconds for the role to expire|
|properties|object|false|none|Optional properties to accept key-value pair|
|» **additionalProperties**|string|false|none|Optional properties to accept key-value pair|

#### Enumerated Values

|Property|Value|
|---|---|
|operation|GRANT|
|operation|REVOKE|

<h2 id="tocS_GetAllDatabasesResponseBody">GetAllDatabasesResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemagetalldatabasesresponsebody"></a>
<a id="schema_GetAllDatabasesResponseBody"></a>
<a id="tocSgetalldatabasesresponsebody"></a>
<a id="tocsgetalldatabasesresponsebody"></a>

```json
{
  "results": [
    {
      "databaseId": "my_database",
      "clusterId": "my_cluster",
      "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
      "properties": {
        "retention.days": "30"
      }
    }
  ],
  "pageResults": {
    "totalElements": 0,
    "totalPages": 0,
    "size": 0,
    "content": [
      {
        "databaseId": "my_database",
        "clusterId": "my_cluster",
        "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
        "properties": {
          "retention.days": "30"
        }
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|results|[[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)]|false|read-only|List of Database objects|
|pageResults|[PageGetDatabaseResponseBody](#schemapagegetdatabaseresponsebody)|false|none|Page of Database objects|

<h2 id="tocS_PageGetDatabaseResponseBody">PageGetDatabaseResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemapagegetdatabaseresponsebody"></a>
<a id="schema_PageGetDatabaseResponseBody"></a>
<a id="tocSpagegetdatabaseresponsebody"></a>
<a id="tocspagegetdatabaseresponsebody"></a>

```json
{
  "totalElements": 0,
  "totalPages": 0,
  "size": 0,
  "content": [
    {
      "databaseId": "my_database",
      "clusterId": "my_cluster",
      "databaseUUID": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
      "properties": {
        "retention.days": "30"
      }
    }
  ],
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": true,
    "unsorted": true
  },
  "numberOfElements": 0,
  "pageable": {
    "offset": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "pageNumber": 0,
    "pageSize": 0,
    "paged": true,
    "unpaged": true
  },
  "first": true,
  "last": true,
  "empty": true
}

```

Page of Database objects

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|totalElements|integer(int64)|false|none|none|
|totalPages|integer(int32)|false|none|none|
|size|integer(int32)|false|none|none|
|content|[[GetDatabaseResponseBody](#schemagetdatabaseresponsebody)]|false|none|none|
|number|integer(int32)|false|none|none|
|sort|[SortObject](#schemasortobject)|false|none|none|
|numberOfElements|integer(int32)|false|none|none|
|pageable|[PageableObject](#schemapageableobject)|false|none|none|
|first|boolean|false|none|none|
|last|boolean|false|none|none|
|empty|boolean|false|none|none|

<h2 id="tocS_GetAllSoftDeletedTablesResponseBody">GetAllSoftDeletedTablesResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemagetallsoftdeletedtablesresponsebody"></a>
<a id="schema_GetAllSoftDeletedTablesResponseBody"></a>
<a id="tocSgetallsoftdeletedtablesresponsebody"></a>
<a id="tocsgetallsoftdeletedtablesresponsebody"></a>

```json
{
  "pageResults": {
    "totalElements": 0,
    "totalPages": 0,
    "size": 0,
    "content": [
      {
        "tableId": "my_table",
        "databaseId": "my_database",
        "deletedAtMs": 1753915710,
        "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
        "purgeAfterMs": 1753915710
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|pageResults|[PageGetSoftDeletedTableResponseBody](#schemapagegetsoftdeletedtableresponsebody)|false|none|Page of soft deleted tables matching the filters by database|

<h2 id="tocS_GetSoftDeletedTableResponseBody">GetSoftDeletedTableResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemagetsoftdeletedtableresponsebody"></a>
<a id="schema_GetSoftDeletedTableResponseBody"></a>
<a id="tocSgetsoftdeletedtableresponsebody"></a>
<a id="tocsgetsoftdeletedtableresponsebody"></a>

```json
{
  "tableId": "my_table",
  "databaseId": "my_database",
  "deletedAtMs": 1753915710,
  "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
  "purgeAfterMs": 1753915710
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|tableId|string|false|read-only|Unique Resource identifier for a table within a Database|
|databaseId|string|false|read-only|Unique Resource identifier for the Database containing the Table|
|deletedAtMs|integer(int64)|false|read-only|Unix Timestamp in milliseconds when the table was dropped|
|tableLocation|string|false|read-only|Location of Table in File System / Blob Store|
|purgeAfterMs|integer(int64)|false|read-only|Unix Timestamp in milliseconds after which the table is marked for permanent deletion|

<h2 id="tocS_PageGetSoftDeletedTableResponseBody">PageGetSoftDeletedTableResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemapagegetsoftdeletedtableresponsebody"></a>
<a id="schema_PageGetSoftDeletedTableResponseBody"></a>
<a id="tocSpagegetsoftdeletedtableresponsebody"></a>
<a id="tocspagegetsoftdeletedtableresponsebody"></a>

```json
{
  "totalElements": 0,
  "totalPages": 0,
  "size": 0,
  "content": [
    {
      "tableId": "my_table",
      "databaseId": "my_database",
      "deletedAtMs": 1753915710,
      "tableLocation": "<fs>://<hostname>/<openhouse_namespace>/<database_name>/<tableUUID>/metadata/<uuid>.metadata.json",
      "purgeAfterMs": 1753915710
    }
  ],
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": true,
    "unsorted": true
  },
  "numberOfElements": 0,
  "pageable": {
    "offset": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "pageNumber": 0,
    "pageSize": 0,
    "paged": true,
    "unpaged": true
  },
  "first": true,
  "last": true,
  "empty": true
}

```

Page of soft deleted tables matching the filters by database

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|totalElements|integer(int64)|false|none|none|
|totalPages|integer(int32)|false|none|none|
|size|integer(int32)|false|none|none|
|content|[[GetSoftDeletedTableResponseBody](#schemagetsoftdeletedtableresponsebody)]|false|none|none|
|number|integer(int32)|false|none|none|
|sort|[SortObject](#schemasortobject)|false|none|none|
|numberOfElements|integer(int32)|false|none|none|
|pageable|[PageableObject](#schemapageableobject)|false|none|none|
|first|boolean|false|none|none|
|last|boolean|false|none|none|
|empty|boolean|false|none|none|

<h2 id="tocS_AclPolicy">AclPolicy</h2>
<!-- backwards compatibility -->
<a id="schemaaclpolicy"></a>
<a id="schema_AclPolicy"></a>
<a id="tocSaclpolicy"></a>
<a id="tocsaclpolicy"></a>

```json
{
  "principal": "string",
  "role": "string",
  "expirationEpochTimeSeconds": 0,
  "properties": {
    "property1": "string",
    "property2": "string"
  }
}

```

List of acl policies associated with table/database

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|principal|string|false|read-only|Principal with the role on the table/database|
|role|string|false|read-only|Role associated with the principal|
|expirationEpochTimeSeconds|integer(int64)|false|read-only|Optional epoch time in seconds for the role to expire|
|properties|object|false|read-only|Optional properties to accept key-value pair|
|» **additionalProperties**|string|false|none|Optional properties to accept key-value pair|

<h2 id="tocS_GetAclPoliciesResponseBody">GetAclPoliciesResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemagetaclpoliciesresponsebody"></a>
<a id="schema_GetAclPoliciesResponseBody"></a>
<a id="tocSgetaclpoliciesresponsebody"></a>
<a id="tocsgetaclpoliciesresponsebody"></a>

```json
{
  "results": [
    {
      "principal": "string",
      "role": "string",
      "expirationEpochTimeSeconds": 0,
      "properties": {
        "property1": "string",
        "property2": "string"
      }
    }
  ]
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|results|[[AclPolicy](#schemaaclpolicy)]|false|read-only|List of acl policies associated with table/database|

