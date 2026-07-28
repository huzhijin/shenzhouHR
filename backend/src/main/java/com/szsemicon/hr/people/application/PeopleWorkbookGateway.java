package com.szsemicon.hr.people.application;

import com.szsemicon.hr.people.domain.PeopleModels.ImportIssue;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateVersion;
import java.util.List;
import java.util.Map;

public interface PeopleWorkbookGateway {

    List<TemplateVersion> listTemplates(TemplateType type);

    TemplateWorkbook getTemplate(TemplateType type, String version);

    ParsedWorkbook parse(
            byte[] content,
            TemplateType type,
            List<com.szsemicon.hr.people.domain.PeopleModels.MappingEntry> mapping);

    byte[] createErrorReport(List<ImportIssue> issues);

    record TemplateWorkbook(TemplateVersion metadata, byte[] content) {
    }

    record ParsedWorkbook(List<Map<String, Object>> rows) {
    }
}
