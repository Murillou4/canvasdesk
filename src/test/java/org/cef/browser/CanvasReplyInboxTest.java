package org.cef.browser;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanvasReplyInboxTest {
    @Test void responseBeforeNativeIdIsRetained(){var box=new CanvasReplyInbox();var id=new CompletableFuture<Integer>();var answer=box.expect(id);box.received(7,true,"early");assertFalse(answer.isDone());id.complete(7);assertEquals("early",answer.join());}
    @Test void nativeIdBeforeResponseCompletesNormally(){var box=new CanvasReplyInbox();var answer=box.expect(CompletableFuture.completedFuture(8));box.received(8,true,"normal");assertEquals("normal",answer.join());}
    @Test void alreadyDeliveredResponseCanPrecedeTheWholeRequestFuture(){var box=new CanvasReplyInbox();box.received(9,true,"fast");assertEquals("fast",box.expect(CompletableFuture.completedFuture(9)).join());}
    @Test void nativeFailuresAndCloseDoNotLeavePendingInput(){var box=new CanvasReplyInbox();var a=box.expect(CompletableFuture.completedFuture(2));box.received(2,false,"rejected");assertThrows(CompletionException.class,a::join);var pending=box.expect(CompletableFuture.completedFuture(3));var noId=box.expect(new CompletableFuture<>());box.close();assertTrue(pending.isCompletedExceptionally());assertTrue(noId.isCompletedExceptionally());}
}
