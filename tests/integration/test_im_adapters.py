"""L3 Integration Tests: IM channel adapter protocol compliance."""


from newsclaw.channels.base import ChannelAdapter
from newsclaw.channels.types import MessageContent, OutgoingMessage


class TestChannelAdapterInterface:
    """Verify the base adapter protocol defines all required methods."""

    def test_abstract_methods_defined(self):
        import inspect

        abstract_methods = set()
        for name, method in inspect.getmembers(ChannelAdapter):
            if getattr(method, "__isabstractmethod__", False):
                abstract_methods.add(name)

        required = {"start", "stop", "send_message", "download_media", "upload_media"}
        assert required.issubset(abstract_methods)

    def test_channel_name_attribute(self):
        assert hasattr(ChannelAdapter, "channel_name")

    def test_convenience_methods_exist(self):
        assert hasattr(ChannelAdapter, "send_text")
        assert hasattr(ChannelAdapter, "send_image")


class TestFeishuAdapterInit:
    def test_import_succeeds(self):
        from newsclaw.channels.adapters.feishu import FeishuAdapter

        assert FeishuAdapter.channel_name == "feishu"

    def test_init_with_credentials(self):
        from newsclaw.channels.adapters.feishu import FeishuAdapter

        adapter = FeishuAdapter(
            app_id="test-app-id",
            app_secret="test-secret",
        )
        assert adapter.channel_name == "feishu"


class TestQQBotAdapterInit:
    def test_import_succeeds(self):
        from newsclaw.channels.adapters.qq_official import QQBotAdapter

        assert QQBotAdapter.channel_name == "qqbot"

    def test_init_with_credentials(self):
        from newsclaw.channels.adapters.qq_official import QQBotAdapter

        adapter = QQBotAdapter(
            app_id="test-app-id",
            app_secret="test-secret",
        )
        assert adapter.channel_name == "qqbot"


class TestOutgoingMessageConstruction:
    def test_text_message(self):
        msg = OutgoingMessage(
            chat_id="123",
            content=MessageContent(text="Hello"),
        )
        assert msg.chat_id == "123"
        assert msg.content.text == "Hello"

    def test_message_with_reply(self):
        msg = OutgoingMessage(
            chat_id="123",
            content=MessageContent(text="Reply"),
            reply_to="msg-456",
        )
        assert msg.reply_to == "msg-456"

    def test_silent_message(self):
        msg = OutgoingMessage(
            chat_id="123",
            content=MessageContent(text="Shh"),
            silent=True,
        )
        assert msg.silent is True
