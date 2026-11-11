using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Pixlink.Services;

/// <summary>One protocol message. See docs/PROTOCOL.md.</summary>
public sealed class Envelope
{
    [JsonPropertyName("t")] public string T { get; set; } = "";
    [JsonPropertyName("id")] public string? Id { get; set; }
    [JsonPropertyName("d")] public JsonObject D { get; set; } = new();

    public static Envelope Of(string type, object? data = null, string? id = null) => new()
    {
        T = type,
        Id = id,
        D = data is null ? new JsonObject() : (JsonSerializer.SerializeToNode(data, JsonOpts) as JsonObject ?? new JsonObject()),
    };

    public string? Str(string key) => D[key]?.GetValueKind() == JsonValueKind.String ? D[key]!.GetValue<string>() : null;
    public int? Int(string key) => D[key] is JsonValue v && v.TryGetValue<int>(out var i) ? i : null;
    public long? Long(string key) => D[key] is JsonValue v && v.TryGetValue<long>(out var l) ? l : null;
    public bool? Bool(string key) => D[key] is JsonValue v && v.TryGetValue<bool>(out var b) ? b : null;
    public JsonArray? Arr(string key) => D[key] as JsonArray;

    public string ToLine() => JsonSerializer.Serialize(this, JsonOpts);

    public static Envelope? Parse(string line)
    {
        try { return JsonSerializer.Deserialize<Envelope>(line, JsonOpts); }
        catch (JsonException) { return null; }
    }

    public static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };
}
