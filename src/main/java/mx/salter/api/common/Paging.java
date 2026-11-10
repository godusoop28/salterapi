package mx.salter.api.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Paging {
    public static final int MAX_SIZE = 200;
    private Paging() {}

    public static Pageable of(Integer page, Integer size, Sort sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? 25 : Math.min(size, MAX_SIZE);
        return PageRequest.of(p, s, sort);
    }
}
