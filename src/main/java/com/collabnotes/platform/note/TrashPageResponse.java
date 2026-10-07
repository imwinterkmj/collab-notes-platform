package com.collabnotes.platform.note;

import java.util.List;

public record TrashPageResponse(List<TrashNoteResponse> items, int limit) {
    public TrashPageResponse { items = List.copyOf(items); }
    @Override public String toString() { return "TrashPageResponse[count=" + items.size() + ", limit=" + limit + "]"; }
}
