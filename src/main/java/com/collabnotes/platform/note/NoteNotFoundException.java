package com.collabnotes.platform.note;

public class NoteNotFoundException extends RuntimeException {
    public NoteNotFoundException() { super("备忘录不存在或不可访问"); }
}
