using Markdig;
using Markdig.Extensions.TaskLists;
using Markdig.Syntax;
using Markdig.Syntax.Inlines;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Media;
using Windows.UI.Text;
using MdBlock = Markdig.Syntax.Block;
using MdTable = Markdig.Extensions.Tables.Table;
using MdTableCell = Markdig.Extensions.Tables.TableCell;
using MdTableRow = Markdig.Extensions.Tables.TableRow;
using XamlInline = Microsoft.UI.Xaml.Documents.Inline;

namespace MHarness.Desktop;

/// <summary>
/// 助手消息的 Markdown 宿主。流式更新时反复 <see cref="SetMarkdown"/> 即可。
/// </summary>
internal sealed class MarkdownBlockHost : StackPanel
{
    public MarkdownBlockHost()
    {
        Spacing = 8;
        HorizontalAlignment = HorizontalAlignment.Stretch;
    }

    public void SetMarkdown(string markdown)
    {
        Children.Clear();
        MarkdownRenderer.Render(this, markdown);
    }
}

/// <summary>
/// 把 CommonMark / GFM 常用语法转成 WinUI 元素。HTML 原样当文本，不执行。
/// </summary>
internal static class MarkdownRenderer
{
    private static readonly MarkdownPipeline Pipeline = new MarkdownPipelineBuilder()
        .UseEmphasisExtras(Markdig.Extensions.EmphasisExtras.EmphasisExtraOptions.Strikethrough)
        .UsePipeTables()
        .UseAutoLinks()
        .UseTaskLists()
        .UseSoftlineBreakAsHardlineBreak()
        .DisableHtml()
        .Build();

    private static readonly FontFamily MonoFont = new("Consolas");

    public static void Render(Panel host, string? markdown)
    {
        if (string.IsNullOrEmpty(markdown))
        {
            return;
        }
        try
        {
            string source = CloseOpenFences(markdown);
            MarkdownDocument document = Markdown.Parse(source, Pipeline);
            foreach (MdBlock block in document)
            {
                AddBlock(host, block, 0);
            }
        }
        catch (Exception)
        {
            host.Children.Add(PlainText(markdown));
        }
    }

    /// <summary>
    /// 流式输出时补上未闭合的围栏，避免半截代码块被当成普通段落。
    /// </summary>
    internal static string CloseOpenFences(string markdown)
    {
        bool open = false;
        char fenceChar = '`';
        int fenceLen = 0;
        int start = 0;
        while (start < markdown.Length)
        {
            int end = markdown.IndexOf('\n', start);
            if (end < 0)
            {
                end = markdown.Length;
            }
            ReadOnlySpan<char> line = markdown.AsSpan(start, end - start);
            if (line.Length > 0 && line[^1] == '\r')
            {
                line = line[..^1];
            }
            if (TryParseFence(line, out char ch, out int len, out bool hasInfo))
            {
                if (!open)
                {
                    open = true;
                    fenceChar = ch;
                    fenceLen = len;
                }
                else if (ch == fenceChar && len >= fenceLen && !hasInfo)
                {
                    open = false;
                }
            }
            start = end < markdown.Length ? end + 1 : markdown.Length;
        }
        if (!open)
        {
            return markdown;
        }
        string closer = new(fenceChar, fenceLen);
        return markdown.EndsWith('\n') ? markdown + closer : markdown + "\n" + closer;
    }

    private static bool TryParseFence(ReadOnlySpan<char> line, out char ch, out int len, out bool hasInfo)
    {
        ch = '\0';
        len = 0;
        hasInfo = false;
        int i = 0;
        while (i < line.Length && line[i] == ' ')
        {
            i++;
            if (i > 3)
            {
                return false;
            }
        }
        if (i >= line.Length)
        {
            return false;
        }
        ch = line[i];
        if (ch is not ('`' or '~'))
        {
            return false;
        }
        int fenceStart = i;
        while (i < line.Length && line[i] == ch)
        {
            i++;
        }
        len = i - fenceStart;
        if (len < 3)
        {
            return false;
        }
        ReadOnlySpan<char> rest = line[i..].Trim();
        hasInfo = !rest.IsEmpty;
        if (ch == '`' && rest.Contains('`'))
        {
            return false;
        }
        return true;
    }

    private static void AddBlock(Panel panel, MdBlock block, int listDepth)
    {
        switch (block)
        {
            case HeadingBlock heading:
                AddHeading(panel, heading);
                break;
            case ParagraphBlock paragraph:
                AddRich(panel, paragraph.Inline, null, null);
                break;
            case CodeBlock code:
                panel.Children.Add(CreateCodeBlock(code));
                break;
            case QuoteBlock quote:
                AddQuote(panel, quote, listDepth);
                break;
            case ListBlock list:
                AddList(panel, list, listDepth);
                break;
            case ThematicBreakBlock:
                panel.Children.Add(CreateRule());
                break;
            case MdTable table:
                panel.Children.Add(CreateTable(table));
                break;
            case LeafBlock leaf when leaf.Inline != null:
                AddRich(panel, leaf.Inline, null, null);
                break;
            case ContainerBlock container:
                foreach (MdBlock child in container)
                {
                    AddBlock(panel, child, listDepth);
                }
                break;
        }
    }

    private static void AddHeading(Panel panel, HeadingBlock heading)
    {
        double size = heading.Level switch
        {
            1 => 26,
            2 => 22,
            3 => 18,
            4 => 16,
            5 => 14,
            _ => 13,
        };
        AddRich(panel, heading.Inline, FontWeights.SemiBold, size);
    }

    private static void AddQuote(Panel panel, QuoteBlock quote, int listDepth)
    {
        var inner = new StackPanel { Spacing = 6, Opacity = 0.9 };
        foreach (MdBlock child in quote)
        {
            AddBlock(inner, child, listDepth);
        }
        var border = new Border
        {
            BorderThickness = new Thickness(3, 0, 0, 0),
            Padding = new Thickness(12, 2, 0, 2),
            Child = inner,
        };
        if (TryThemeBrush("ControlStrongFillColorDefaultBrush", out Brush? bar))
        {
            border.BorderBrush = bar;
        }
        panel.Children.Add(border);
    }

    private static void AddList(Panel panel, ListBlock list, int depth)
    {
        var wrap = new StackPanel { Spacing = 6 };
        if (depth > 0)
        {
            wrap.Margin = new Thickness(8, 0, 0, 0);
        }
        bool ordered = list.IsOrdered;
        int number = 1;
        if (ordered && int.TryParse(list.OrderedStart, out int start) && start > 0)
        {
            number = start;
        }
        foreach (MdBlock item in list)
        {
            if (item is not ListItemBlock listItem)
            {
                continue;
            }
            var row = new Grid { HorizontalAlignment = HorizontalAlignment.Stretch };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            string marker = TryTaskMarker(listItem, out string taskMarker)
                ? taskMarker
                : ordered
                    ? number + "."
                    : "•";
            if (ordered)
            {
                number++;
            }
            var markerBlock = new TextBlock
            {
                Text = marker,
                MinWidth = ordered ? 28 : 18,
                Opacity = 0.7,
                Margin = new Thickness(0, 0, 8, 0),
            };
            var content = new StackPanel { Spacing = 6 };
            foreach (MdBlock child in listItem)
            {
                AddBlock(content, child, depth + 1);
            }
            Grid.SetColumn(content, 1);
            row.Children.Add(markerBlock);
            row.Children.Add(content);
            wrap.Children.Add(row);
        }
        panel.Children.Add(wrap);
    }

    private static bool TryTaskMarker(ListItemBlock item, out string marker)
    {
        marker = "";
        foreach (MdBlock child in item)
        {
            if (child is ParagraphBlock paragraph)
            {
                for (var inline = paragraph.Inline?.FirstChild; inline != null; inline = inline.NextSibling)
                {
                    if (inline is TaskList task)
                    {
                        marker = task.Checked ? "☑" : "☐";
                        return true;
                    }
                    if (inline is LiteralInline literal && string.IsNullOrWhiteSpace(literal.Content.ToString()))
                    {
                        continue;
                    }
                    break;
                }
            }
            break;
        }
        return false;
    }

    private static UIElement CreateTable(MdTable table)
    {
        var grid = new Grid { HorizontalAlignment = HorizontalAlignment.Stretch };
        int columns = table.ColumnDefinitions.Count;
        if (columns == 0)
        {
            foreach (MdBlock rowBlock in table)
            {
                if (rowBlock is MdTableRow row)
                {
                    columns = Math.Max(columns, row.Count);
                }
            }
        }
        for (int i = 0; i < columns; i++)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        }
        int r = 0;
        TryThemeBrush("CardStrokeColorDefaultBrush", out Brush? stroke);
        TryThemeBrush("CardBackgroundFillColorSecondaryBrush", out Brush? headerBg);
        foreach (MdBlock rowBlock in table)
        {
            if (rowBlock is not MdTableRow row)
            {
                continue;
            }
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            int c = 0;
            foreach (MdBlock cellBlock in row)
            {
                if (cellBlock is not MdTableCell cell)
                {
                    continue;
                }
                var inner = new StackPanel { Spacing = 4 };
                foreach (MdBlock child in cell)
                {
                    AddBlock(inner, child, 0);
                }
                var border = new Border
                {
                    BorderThickness = new Thickness(1),
                    Padding = new Thickness(8, 6, 8, 6),
                    Child = inner,
                    BorderBrush = stroke,
                    Background = row.IsHeader ? headerBg : null,
                };
                Grid.SetRow(border, r);
                Grid.SetColumn(border, c);
                if (cell.ColumnSpan > 1)
                {
                    Grid.SetColumnSpan(border, cell.ColumnSpan);
                }
                grid.Children.Add(border);
                c += Math.Max(1, cell.ColumnSpan);
            }
            r++;
        }
        return grid;
    }

    private static UIElement CreateCodeBlock(CodeBlock block)
    {
        string code = block.Lines.ToString();
        if (code.EndsWith('\n'))
        {
            code = code[..^1];
        }
        string? lang = null;
        if (block is FencedCodeBlock fenced && !string.IsNullOrWhiteSpace(fenced.Info))
        {
            lang = fenced.Info.Trim();
            int space = lang.IndexOfAny([' ', '\t']);
            if (space >= 0)
            {
                lang = lang[..space];
            }
        }
        var body = new TextBlock
        {
            Text = code,
            FontFamily = MonoFont,
            FontSize = 13,
            IsTextSelectionEnabled = true,
            TextWrapping = TextWrapping.Wrap,
        };
        var stack = new StackPanel { Spacing = 6 };
        if (!string.IsNullOrEmpty(lang))
        {
            stack.Children.Add(new TextBlock
            {
                Text = lang,
                FontSize = 11,
                Opacity = 0.55,
            });
        }
        stack.Children.Add(body);
        var border = new Border
        {
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12, 10, 12, 10),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Child = stack,
        };
        if (TryThemeBrush("CardBackgroundFillColorSecondaryBrush", out Brush? background))
        {
            border.Background = background;
        }
        return border;
    }

    private static UIElement CreateRule()
    {
        var rule = new Border
        {
            Height = 1,
            Margin = new Thickness(0, 6, 0, 6),
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        if (TryThemeBrush("DividerStrokeColorDefaultBrush", out Brush? stroke))
        {
            rule.Background = stroke;
        }
        return rule;
    }

    private static void AddRich(Panel panel, ContainerInline? inline, FontWeight? weight, double? size)
    {
        if (inline?.FirstChild == null)
        {
            return;
        }
        var rtb = new RichTextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            IsTextSelectionEnabled = true,
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        if (weight != null)
        {
            rtb.FontWeight = weight.Value;
        }
        if (size != null)
        {
            rtb.FontSize = size.Value;
        }
        var paragraph = new Paragraph { Margin = new Thickness(0) };
        AddInlines(paragraph.Inlines, inline);
        rtb.Blocks.Add(paragraph);
        panel.Children.Add(rtb);
    }

    private static void AddInlines(InlineCollection dest, ContainerInline container)
    {
        foreach (Markdig.Syntax.Inlines.Inline inline in container)
        {
            AddInline(dest, inline);
        }
    }

    private static void AddInline(InlineCollection dest, Markdig.Syntax.Inlines.Inline inline)
    {
        switch (inline)
        {
            case LiteralInline literal:
                dest.Add(new Run { Text = literal.Content.ToString() });
                break;
            case CodeInline code:
                dest.Add(new Run
                {
                    Text = code.Content,
                    FontFamily = MonoFont,
                    FontSize = 13,
                });
                break;
            case LineBreakInline:
                dest.Add(new LineBreak());
                break;
            case EmphasisInline emphasis:
                dest.Add(CreateEmphasis(emphasis));
                break;
            case LinkInline link when link.IsImage:
                dest.Add(new Run { Text = ImageLabel(link), FontStyle = FontStyle.Italic });
                break;
            case LinkInline link:
                dest.Add(CreateHyperlink(link));
                break;
            case AutolinkInline autolink:
                dest.Add(CreateHyperlink(autolink.Url, autolink.Url, autolink.IsEmail));
                break;
            case HtmlEntityInline entity:
                dest.Add(new Run { Text = entity.Transcoded.ToString() });
                break;
            case TaskList:
                break;
            case HtmlInline html:
                dest.Add(new Run { Text = html.Tag });
                break;
            case ContainerInline nested:
                AddInlines(dest, nested);
                break;
            default:
                string? text = inline.ToString();
                if (!string.IsNullOrEmpty(text))
                {
                    dest.Add(new Run { Text = text });
                }
                break;
        }
    }

    private static XamlInline CreateEmphasis(EmphasisInline emphasis)
    {
        if (emphasis.DelimiterChar == '~')
        {
            var strike = new Span { TextDecorations = TextDecorations.Strikethrough };
            AddInlines(strike.Inlines, emphasis);
            return strike;
        }
        if (emphasis.DelimiterCount >= 3)
        {
            var italic = new Italic();
            AddInlines(italic.Inlines, emphasis);
            var bold = new Bold();
            bold.Inlines.Add(italic);
            return bold;
        }
        if (emphasis.DelimiterCount == 2)
        {
            var bold = new Bold();
            AddInlines(bold.Inlines, emphasis);
            return bold;
        }
        var onlyItalic = new Italic();
        AddInlines(onlyItalic.Inlines, emphasis);
        return onlyItalic;
    }

    private static XamlInline CreateHyperlink(LinkInline link)
    {
        string label = PlainText(link);
        if (string.IsNullOrEmpty(label))
        {
            label = link.Url ?? "";
        }
        return CreateHyperlink(link.Url, label, false);
    }

    private static XamlInline CreateHyperlink(string? url, string label, bool email)
    {
        string href = url ?? "";
        if (email && !href.StartsWith("mailto:", StringComparison.OrdinalIgnoreCase))
        {
            href = "mailto:" + href;
        }
        if (!TrySafeUri(href, out Uri? uri) || uri == null)
        {
            return new Run { Text = label };
        }
        var hyperlink = new Hyperlink { NavigateUri = uri };
        hyperlink.Inlines.Add(new Run { Text = label });
        return hyperlink;
    }

    private static string ImageLabel(LinkInline link)
    {
        string alt = PlainText(link);
        return string.IsNullOrEmpty(alt) ? "[图片]" : alt;
    }

    private static string PlainText(ContainerInline? container)
    {
        if (container == null)
        {
            return "";
        }
        var builder = new System.Text.StringBuilder();
        foreach (Markdig.Syntax.Inlines.Inline inline in container)
        {
            switch (inline)
            {
                case LiteralInline literal:
                    builder.Append(literal.Content.ToString());
                    break;
                case CodeInline code:
                    builder.Append(code.Content);
                    break;
                case LineBreakInline:
                    builder.Append(' ');
                    break;
                case ContainerInline nested:
                    builder.Append(PlainText(nested));
                    break;
            }
        }
        return builder.ToString();
    }

    private static bool TrySafeUri(string href, out Uri? uri)
    {
        uri = null;
        if (string.IsNullOrWhiteSpace(href))
        {
            return false;
        }
        if (!Uri.TryCreate(href, UriKind.Absolute, out uri) || uri == null)
        {
            return false;
        }
        return uri.Scheme is "http" or "https" or "mailto";
    }

    private static bool TryThemeBrush(string key, out Brush? brush)
    {
        brush = null;
        if (Application.Current?.Resources.TryGetValue(key, out object value) == true && value is Brush found)
        {
            brush = found;
            return true;
        }
        return false;
    }

    private static TextBlock PlainText(string text)
    {
        return new TextBlock
        {
            Text = text,
            TextWrapping = TextWrapping.Wrap,
            IsTextSelectionEnabled = true,
        };
    }
}
