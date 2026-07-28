package com.szsemicon.hr.people.application;

import java.util.List;

public record PeoplePage<T>(List<T> items, long total, int page, int size) {
}
