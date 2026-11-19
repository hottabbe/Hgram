package xyz.nextalone.nagram.hgram

/**
 * A streaming offline recognizer session shared by the Vosk/sherpa alternatives.
 *
 * The caller feeds bounded 16 kHz PCM chunks and finishes the session when the current
 * speech segment ends. Keeping the interface small lets the transcription pipeline switch
 * between sherpa transducer models and GigaAM CTC without holding the whole recording.
 */
interface HgramSpeechStream {
    fun accept(samples: FloatArray, length: Int)
    fun finish(): String
    fun release()
}
