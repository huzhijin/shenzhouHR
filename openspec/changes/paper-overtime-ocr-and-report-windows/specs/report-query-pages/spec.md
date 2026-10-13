## ADDED Requirements

### Requirement: Overtime query lists paper and OA documents as detail rows

加班统计 SHALL return paper overtime documents alongside OA overtime documents as separate rows in the same sheet, using the existing start–end period filter, overtime-treatment filter, and paging. Each row MUST identify source origin (OA or 纸质). The query GET MUST still read pinned OA-document facts and MUST NOT calculate.

#### Scenario: Paper overtime appears in 加班统计
- **WHEN** an authorized user queries 加班统计 for a range that covers 8-18
- **AND** a paper overtime document for 10012 on 8-18 is in the pin
- **THEN** the page includes that row with source 纸质

#### Scenario: Overtime treatment filter applies to paper rows
- **WHEN** the user filters 加班性质 = 转调休加班
- **THEN** paper 调休 rows are included and paper 加班费 rows are not
