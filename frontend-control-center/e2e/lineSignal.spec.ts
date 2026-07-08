import { test, expect } from '@playwright/test';

test.describe('LineSignal', () => {
  test.beforeEach(async ({ page }) => {
    // 模拟后端 MA 接口不可用，让前端 fallback 到本地 mock，保证测试稳定快速
    await page.route('**/api/signal/ma', (route) => route.abort());
    await page.goto('/');
    await page.getByRole('menuitem', { name: '线路信号' }).click();
    await expect(page.getByRole('heading', { name: '线路信号 - 信号平面布置图' })).toBeVisible();
  });

  test('should display statistics after loading line profile', async ({ page }) => {
    await expect(page.locator('.ant-statistic-title').getByText('站点')).toBeVisible();
    await expect(page.locator('.ant-statistic-title').getByText('信号机')).toBeVisible();
    await expect(page.locator('.ant-statistic-title').getByText('道岔')).toBeVisible();
    await expect(page.locator('.ant-statistic-title').getByText('总里程')).toBeVisible();
  });

  test('should refresh MA and render trains', async ({ page }) => {
    await page.getByRole('button', { name: '刷新 MA' }).click();
    await expect(page.getByText('T001')).toBeVisible();
    await expect(page.getByText('T002')).toBeVisible();
  });

  test('should show degraded alert in degraded mode', async ({ page }) => {
    await page.getByRole('button', { name: '降级测试OFF' }).click();
    await expect(page.getByRole('button', { name: '降级测试ON' })).toBeVisible();
    await page.getByRole('button', { name: '刷新 MA' }).click();
    await expect(page.getByText('MA 降级告警')).toBeVisible();
  });

  test('should open switch control panel', async ({ page }) => {
    await expect(page.getByRole('tab', { name: '道岔' })).toBeVisible();
    await page.getByRole('tab', { name: '道岔' }).click();
  });
});
