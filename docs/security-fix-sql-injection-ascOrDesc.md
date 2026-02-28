# Security Fix: SQL Injection via Unvalidated `ascOrDesc` Parameter

## Vulnerability Summary

| Item              | Detail                                                 |
|-------------------|--------------------------------------------------------|
| **Severity**      | Critical                                               |
| **Type**          | SQL Injection (CWE-89)                                 |
| **Attack Vector** | HTTP query parameter `sort`                            |
| **Root Cause**    | MyBatis `${}` string interpolation without input validation |

## Vulnerability Description

Multiple API endpoints accept a `sort` query parameter in the format `field.direction` (e.g., `time.desc`). The application splits this value by `.` to extract the field name and sort direction (`ascOrDesc`).

**The field name (`orderBy`) was validated** via switch statements or regex whitelists. However, **the sort direction (`ascOrDesc`) was only lowercased, never validated**, before being passed directly into MyBatis `${ascOrDesc}` string interpolation in SQL `ORDER BY` clauses.

### Data Flow (Before Fix)

```
User Request                  Service Layer                  MyBatis Mapper
─────────────────────────────────────────────────────────────────────────────

?sort=time.desc               sort.split(".", 2)             ORDER BY ${orderByStr} ${ascOrDesc}
                                  ↓
                              orderBy = "time"
                              ascOrDesc = "desc"    ──────→  ORDER BY block_timestamp desc      ✓ Safe
                              
?sort=time.desc;DROP TABLE..  sort.split(".", 2)             ORDER BY ${orderByStr} ${ascOrDesc}
                                  ↓
                              orderBy = "time"
                              ascOrDesc = "desc;    ──────→  ORDER BY block_timestamp desc;     ✗ SQL Injection!
                                DROP TABLE..."                  DROP TABLE ckb_transaction;--
```

### Affected Mapper SQL Locations

The `${ascOrDesc}` interpolation appears in the following MyBatis mapper locations:

| Mapper File                        | SQL Location                       |
|------------------------------------|------------------------------------|
| `CkbTransactionMapper.xml`         | Line 113, 132, 176                 |
| `UdtHolderAllocationsMapper.xml`   | Line 31                            |
| `DobExtendMapper.java`             | Line 42 (`@Select`), Line 108 (`@Select`) |
| `OutputMapper.java`                | Line 45, 59                        |
| `Address24hTransactionMapper.java` | Line 27, 35, 48, 62                |

### Affected API Endpoints

| API Endpoint                                           | Service/Facade Method                                           |
|--------------------------------------------------------|-----------------------------------------------------------------|
| `GET /api/v1/address_transactions/{address}`           | `CkbTransactionServiceImpl.getAddressTransactions()`            |
| `GET /api/v1/udt_transactions/{typeScriptHash}`        | `CkbTransactionServiceImpl.getUdtTransactions()`                |
| `GET /api/v1/udts`                                     | `UdtHolderAllocationsServiceImpl.udtListStatistic()`            |
| `GET /api/v1/nft/collections`                          | `NftCacheFacadeImpl.loadCollectionsFromDatabase()`              |
| `GET /api/v1/nft/collections/{typeScriptHash}/holders` | `NftCacheFacadeImpl.loadNftHoldersFromDatabase()`               |

## Reproduction Steps (Before Fix)

### Step 1: Identify the Vulnerable Parameter

The `sort` query parameter is present on API endpoints. Example normal request:

```bash
curl "http://localhost:8081/api/v1/address_transactions/{address}?sort=time.desc&page=1&pageSize=10"
```

### Step 2: Craft the Malicious Payload

The sort direction portion (after `.`) is not validated. An attacker can inject SQL:

```bash
# Attempt to extract database version via error-based injection
curl "http://localhost:8081/api/v1/address_transactions/{address}?sort=time.desc%3BSELECT+version()--&page=1&pageSize=10"

# Attempt time-based blind injection
curl "http://localhost:8081/api/v1/address_transactions/{address}?sort=time.desc%3BSELECT+pg_sleep(5)--&page=1&pageSize=10"
```

### Step 3: Observe the Result

Before the fix, the injected SQL would be interpolated directly into the query:

```sql
-- Normal: 
ORDER BY block_timestamp desc

-- Injected:
ORDER BY block_timestamp desc;SELECT pg_sleep(5)--
```

## Fix Description

### Fix Strategy: Whitelist Validation

Added strict whitelist validation for `ascOrDesc` in all 4 service/facade locations that construct the sort parameters before passing them to mappers.

### Fix Code

The following validation was added immediately after extracting `ascOrDesc` from user input:

```java
String ascOrDesc = sortParts.length > 1 ? sortParts[1].toLowerCase() : "desc";
// Whitelist validation — only "asc" and "desc" are allowed
if (!"asc".equals(ascOrDesc) && !"desc".equals(ascOrDesc)) {
    ascOrDesc = "desc";
}
```

### Files Modified

| File                                                              | Location     | Change                            |
|-------------------------------------------------------------------|--------------|-----------------------------------|
| `CkbTransactionServiceImpl.java`                                 | Line 387-389 | Added `ascOrDesc` whitelist check |
| `CkbTransactionServiceImpl.java`                                 | Line 528-530 | Added `ascOrDesc` whitelist check |
| `UdtHolderAllocationsServiceImpl.java`                           | Line 93-95   | Added `ascOrDesc` whitelist check |
| `NftCacheFacadeImpl.java`                                        | Line 114-116 | Added `ascOrDesc` whitelist check |
| `NftCacheFacadeImpl.java`                                        | Line 256-258 | Added `ascOrDesc` whitelist check |

### Why Whitelist Validation (Not Parameterized Query)

MyBatis uses two interpolation syntaxes:
- `#{param}` — parameterized (safe, uses PreparedStatement)
- `${param}` — string interpolation (unsafe, direct concatenation)

SQL `ORDER BY` clauses do **not** support parameterized column names or sort directions. Therefore `${...}` is required here. The fix is to ensure only safe values reach the `${}` interpolation through strict whitelist validation.

## Verification Steps (After Fix)

### Step 1: Verify Normal Sorting Still Works

```bash
# Ascending sort
curl "http://localhost:8081/api/v1/address_transactions/{address}?sort=time.asc&page=1&pageSize=10"
# Expected: Results sorted by time ascending ✓

# Descending sort
curl "http://localhost:8081/api/v1/address_transactions/{address}?sort=time.desc&page=1&pageSize=10"
# Expected: Results sorted by time descending ✓
```

### Step 2: Verify Malicious Input Is Rejected

```bash
# SQL injection attempt
curl "http://localhost:8081/api/v1/address_transactions/{address}?sort=time.desc%3BSELECT+1--&page=1&pageSize=10"
# Expected: Invalid value is replaced with "desc", query executes safely ✓

# Another injection attempt
curl "http://localhost:8081/api/v1/udts?sort=typeScriptId.asc%27+OR+1%3D1--&page=1&pageSize=10"
# Expected: Invalid value is replaced with "desc", query executes safely ✓
```

### Step 3: Verify Default Behavior

```bash
# No sort direction provided
curl "http://localhost:8081/api/v1/address_transactions/{address}?sort=time&page=1&pageSize=10"
# Expected: Defaults to "desc" ✓
```
