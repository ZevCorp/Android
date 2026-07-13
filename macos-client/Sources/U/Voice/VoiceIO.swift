import AVFoundation
import Foundation

/// TTS con el sintetizador integrado de macOS (AVSpeechSynthesizer). I/O puro del cliente: hablar.
/// (STT por voz se puede añadir con el framework Speech en una fase siguiente; de momento el dictado
/// se hace por el campo de texto.) Ninguna transcripción se interpreta aquí — iría tal cual al cerebro.
final class VoiceIO {
    private let synth = AVSpeechSynthesizer()

    func speak(_ text: String) {
        guard !text.isEmpty else { return }
        synth.stopSpeaking(at: .immediate)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "es-ES") ?? AVSpeechSynthesisVoice(language: "en-US")
        synth.speak(u)
    }
}
