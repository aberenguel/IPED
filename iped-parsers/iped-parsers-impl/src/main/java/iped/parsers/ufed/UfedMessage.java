package iped.parsers.ufed;

import iped.parsers.whatsapp.Message;
import iped.properties.ExtraProperties;

public class UfedMessage extends Message {

    public static final String SYSTEM_MESSAGE = "System Message"; //$NON-NLS-1$

    public static final String MESSAGE_TO = ExtraProperties.COMMUNICATION_TO;
    public static final String MESSAGE_FROM = ExtraProperties.COMMUNICATION_FROM;

    private String transcription;
    private String transcriptConfidence;
    private String mediatrueExtension;

    public String getMediaTrueExt() {
        return mediatrueExtension;
    }

    public void setMediaTrueExt(String trueExtension) {
        this.mediatrueExtension = trueExtension;
    }

    public String getTranscription() {
        return transcription;
    }

    public void setTranscription(String transcription) {
        this.transcription = transcription;
    }

    public String getTranscriptConfidence() {
        return transcriptConfidence;
    }

    public void setTranscriptConfidence(String transcriptConfidence) {
        this.transcriptConfidence = transcriptConfidence;
    }

    @Override
    public boolean isSystemMessage() {
        return SYSTEM_MESSAGE.equals(this.getRemoteResource());
    }

}
