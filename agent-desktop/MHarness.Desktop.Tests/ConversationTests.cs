using Xunit;

namespace MHarness.Desktop.Tests;

public class ConversationTests
{
    [Fact]
    public void MakeTitle_foldsWhitespaceAndCapsAt40()
    {
        Assert.Equal("新对话", Conversation.MakeTitle("   "));
        Assert.Equal("修复 登录", Conversation.MakeTitle("  修复  登录  "));
        Assert.Equal(40, Conversation.MakeTitle(new string('a', 80)).Length);
    }

    [Fact]
    public void FormatRelative_justNow()
    {
        Assert.Equal("刚刚", Conversation.FormatRelative(DateTimeOffset.UtcNow));
    }

    [Fact]
    public void AppendToolLog_capsLength()
    {
        var conversation = new Conversation();
        conversation.AppendToolLog(new string('x', Conversation.MaxToolLogChars + 50));
        Assert.Equal(Conversation.MaxToolLogChars, conversation.ToolLog.Length);
        Assert.StartsWith("x", conversation.ToolLog);
    }

    [Fact]
    public void Group_ordersByLatestAndFilters()
    {
        var a = new Conversation { Title = "登录", Workspace = @"C:\proj\one", UpdatedAt = DateTimeOffset.UtcNow.AddHours(-2) };
        var b = new Conversation { Title = "支付", Workspace = @"C:\proj\one", UpdatedAt = DateTimeOffset.UtcNow };
        var c = new Conversation { Title = "空白", Workspace = "", UpdatedAt = DateTimeOffset.UtcNow.AddDays(-1) };
        List<ConversationGroup> groups = ConversationStore.Group(new[] { a, b, c }, "登");
        Assert.Single(groups);
        Assert.Equal("one", groups[0].Key);
        Assert.Equal("登录", groups[0].Items[0].Title);
    }

    [Fact]
    public void Store_roundTripAndSkipCorrupt()
    {
        string dir = Path.Combine(Path.GetTempPath(), "m-harness-tests-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new ConversationStore(dir);
            var conversation = new Conversation { Title = "测试会话", Workspace = @"D:\repo" };
            store.Save(conversation);
            File.WriteAllText(Path.Combine(dir, "bad.json"), "{not-json");
            var again = new ConversationStore(dir);
            again.Load();
            Assert.Single(again.All);
            Assert.Equal("测试会话", again.All[0].Title);
            Assert.True(again.Delete(conversation.Id));
            Assert.Empty(again.All);
        }
        finally
        {
            if (Directory.Exists(dir))
            {
                Directory.Delete(dir, recursive: true);
            }
        }
    }
}
