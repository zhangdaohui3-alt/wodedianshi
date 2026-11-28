package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackErrorPolicyTest {
    private void expect(String name,int status,boolean skip,long cooldown){
        PlaybackErrorPolicy.Disposition decision=PlaybackErrorPolicy.classify(name,status);
        assertEquals(name+" / "+status,skip,decision.skipSameUrlVlcRetry);
        assertEquals(name+" / "+status,cooldown,decision.cooldownMs);
    }

    @Test public void networkErrorsSkipEngineRetryWithShortCooldown(){
        expect("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",0,true,30000L);
        expect(" error_code_io_network_connection_timeout ",0,true,30000L);
        expect("ERROR_CODE_IO_FILE_NOT_FOUND",0,true,120000L);
        expect("ERROR_CODE_BEHIND_LIVE_WINDOW",0,true,120000L);
    }

    @Test public void httpEvidenceTakesPrecedenceOverErrorName(){
        for(String name:new String[]{null,"ERROR_CODE_IO_BAD_HTTP_STATUS",
                "ERROR_CODE_DECODING_FAILED","ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"}){
            for(int status:new int[]{404,410})expect(name,status,true,600000L);
            expect(name,429,true,60000L);
            for(int status:new int[]{500,503,599})expect(name,status,true,30000L);
        }
        for(int status:new int[]{0,401,403,499,600})
            expect("ERROR_CODE_IO_BAD_HTTP_STATUS",status,false,120000L);
        expect("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",403,true,30000L);
    }

    @Test public void compatibilityErrorsRetainEngineFallback(){
        for(String name:new String[]{"ERROR_CODE_DECODING_FAILED",
                "ERROR_CODE_DECODER_INIT_FAILED","ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"})
            expect(name,0,false,600000L);
    }

    @Test public void unknownAndMissingErrorsRemainConservative(){
        for(String name:new String[]{null,"","UNKNOWN_ERROR"})
            expect(name,0,false,120000L);
    }

    @Test public void endedLiveWithPriorAudioVideoOutputSkipsEngineRetry() {
        assertFalse(PlaybackErrorPolicy.shouldTryVlcForEndedLive(true,true));
        assertTrue(PlaybackErrorPolicy.shouldTryVlcForEndedLive(true,false));
        assertTrue(PlaybackErrorPolicy.shouldTryVlcForEndedLive(false,true));
        assertTrue(PlaybackErrorPolicy.shouldTryVlcForEndedLive(false,false));
    }
}
