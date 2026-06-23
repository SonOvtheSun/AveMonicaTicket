from locust import HttpUser, task, between

class TicketUser(HttpUser):
    # 模拟用户操作的停顿时间 (1~3秒)
    wait_time = between(1, 3)

    # 每次启动时先拿一个假 token（假设）
    def on_start(self):
        self.headers = {"Authorization": "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwiaWF0IjoxNzgxNzc3NTI4LCJleHAiOjE3ODE4NjM5Mjh9.UDhpgr0lciMJsZu_VSdGk2JhGYwlVfGM8Mn1QK6hg_U"}

    @task(3) # 权重为3：绝大多数人在疯狂刷新演出详情页
    def view_event_detail(self):
        # 例如狂刷 Ave Mujica 的演出详情
        self.client.get("/api/event/detail/10")

    @task(1) # 权重为1：部分人点击了“想看”或“关注”
    def toggle_favorite(self):
        self.client.post("/api/favorite/toggle", json={"targetId": 1, "type": 1}, headers=self.headers)