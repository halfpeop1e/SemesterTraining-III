import { test, expect } from '@playwright/test';

test.describe('Dashboard', () => {
  test('should display system status instead of placeholder', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '系统仪表盘' })).toBeVisible();
    await expect(page.locator('.ant-statistic-title').getByText('在线列车')).toBeVisible();
    await expect(page.locator('.ant-statistic-title').getByText('当前告警')).toBeVisible();
    await expect(page.getByText('系统健康度', { exact: true })).toBeVisible();
    await expect(page.locator('.ant-statistic-title').getByText('仿真时间')).toBeVisible();
    await expect(page.getByText('最近信号事件')).toBeVisible();
  });

  test('should provide quick entry cards', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('button', { name: '线路信号' })).toBeVisible();
    await expect(page.getByRole('button', { name: '调度控制' })).toBeVisible();
    await expect(page.getByRole('button', { name: '能源评估' })).toBeVisible();
  });
});
