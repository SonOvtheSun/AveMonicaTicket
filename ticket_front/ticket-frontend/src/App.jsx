import { useState } from 'react'
import { Button, Form, Input, message, Card } from 'antd'
import axios from 'axios'
import './App.css'

function App() {
  const [loading, setLoading] = useState(false);

  // 点击注册按钮触发的函数
  const onFinish = async (values) => {
    setLoading(true);
    try {
      // 往后端发送 POST 请求！
      const response = await axios.post('/api/user/register', {
        username: values.username,
        password: values.password
      });

      // 判断后端返回的 Result 对象中的 code
      if (response.data.code === 200) {
        message.success('🎉 注册成功！欢迎来到 Ave Monica 票务系统！');
      } else {
        message.error(response.data.message || '注册失败');
      }
    } catch (error) {
      message.error('网络请求失败，请检查后端是否启动！');
    } finally {
      setLoading(false);
    }
  };

  return (
      <div style={{ display: 'flex', justifyContent: 'center', marginTop: '100px' }}>
        <Card title="🎫 漫展抢票系统 - 账号注册" style={{ width: 400, boxShadow: '0 4px 8px rgba(0,0,0,0.1)' }}>

          <Form name="register_form" layout="vertical" onFinish={onFinish}>
            <Form.Item
                label="账号"
                name="username"
                rules={[{ required: true, message: '请输入你的账号！' }]}
            >
              <Input placeholder="起一个响亮的代号" />
            </Form.Item>

            <Form.Item
                label="密码"
                name="password"
                rules={[{ required: true, message: '请输入你的密码！' }]}
            >
              <Input.Password placeholder="切勿泄露给其他人" />
            </Form.Item>

            <Form.Item>
              <Button type="primary" htmlType="submit" loading={loading} block>
                立即注册
              </Button>
            </Form.Item>
          </Form>

        </Card>
      </div>
  )
}

export default App