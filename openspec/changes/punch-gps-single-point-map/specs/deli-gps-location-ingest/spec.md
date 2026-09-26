## ADDED Requirements

### Requirement: GPS and out-work punches persist location evidence

When the Deli adapter ingests a check-in whose `check_type` is `gps` or `out_work`, the system SHALL persist a bounded location summary and any parseable longitude/latitude from `check_data` onto `raw_attendance_fact`. Photo, raw `check_data` JSON, and other non-location fields MUST still be dropped. Fingerprint/card/face punches MUST keep `locationSummary` empty and coordinate status `MISSING`.

The source coordinate system SHALL be stored as an explicit tag (`WGS84`, `GCJ02`, `BD09`, or `UNKNOWN`). Until the Deli payload is confirmed, new GPS rows MAY use `UNKNOWN`. Unknown, missing, incomplete, or out-of-bounds values MUST NOT be converted and MUST NOT populate `map_longitude` / `map_latitude`.

#### Scenario: GPS punch stores address and raw coordinates
- **WHEN** Deli returns `check_type=gps` with `check_data` containing a location text and numeric longitude/latitude
- **THEN** the raw fact has non-null `locationSummary`, `longitude_raw`, `latitude_raw`
- **AND** `verificationMethod` is `gps`
- **AND** photo bytes or photo references are not stored
- **AND** `forbiddenPayloadDropped` remains true for the dropped photo payload

#### Scenario: Card punch still has no location
- **WHEN** Deli returns `check_type=fp` or `card` with no coordinates
- **THEN** `locationSummary` is null
- **AND** `coordinate_validation_status` is `MISSING`
- **AND** map columns stay null

#### Scenario: Unknown coordinate system does not produce a map point
- **WHEN** GPS coordinates are present but `source_coordinate_system` is `UNKNOWN`
- **THEN** `coordinate_validation_status` is `UNKNOWN_SYSTEM`
- **AND** `coordinate_conversion_status` is `NOT_APPLICABLE`
- **AND** `map_longitude` and `map_latitude` are null

#### Scenario: Existing non-GPS ingest contract is unchanged
- **WHEN** a CHECKIN page contains only fingerprint punches
- **THEN** ingest, identity matching, and watermark advance behave as before
- **AND** no location columns are required for those rows to commit

### Requirement: Location ingest does not rewrite historical V1 facts in place

Pre-coordinate or location-less raw facts MUST remain immutable. Replaying the same Deli source identity SHALL NOT silently replace an old row to inject coordinates. Filling history, if ever required, MUST be a separate controlled replay that creates new source versions, not an in-place update of committed facts.

#### Scenario: Same source id without a new source version is a collision
- **WHEN** a previously ingested punch is fetched again with the same source business key and source version
- **THEN** the adapter treats it as the existing identity
- **AND** it does not overwrite the stored coordinate columns of the committed row
