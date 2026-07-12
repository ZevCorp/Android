using System.Speech.Recognition;
using System.Speech.Synthesis;

namespace U.WindowsClient.Voice;

/// <summary>
/// TTS y STT con los motores integrados de Windows (System.Speech). Es I/O puro del cliente: hablar y
/// escuchar. Ninguna transcripción se interpreta aquí — el texto reconocido se manda tal cual al
/// cerebro. (En Android esto lo hacían OpenAiTts/Transcribers; llevarlo al servidor es una fase
/// posterior si se quiere mayor calidad de voz.)
/// </summary>
public sealed class VoiceIO : IDisposable
{
    private readonly SpeechSynthesizer _tts = new();

    public VoiceIO()
    {
        try { _tts.SelectVoiceByHints(VoiceGender.Female, VoiceAge.Adult); } catch { }
    }

    public void Speak(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        try { _tts.SpeakAsyncCancelAll(); _tts.SpeakAsync(text); } catch { }
    }

    /// <summary>Escucha una frase por el micrófono y devuelve el texto (o "" si no reconoció).</summary>
    public async Task<string> ListenOnceAsync(CancellationToken ct)
    {
        return await Task.Run(() =>
        {
            try
            {
                using var rec = new SpeechRecognitionEngine();
                rec.LoadGrammar(new DictationGrammar());
                rec.SetInputToDefaultAudioDevice();
                var result = rec.Recognize(TimeSpan.FromSeconds(8));
                return result?.Text ?? "";
            }
            catch { return ""; }
        }, ct);
    }

    public void Dispose() => _tts.Dispose();
}
