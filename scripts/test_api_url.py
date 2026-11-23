import unittest
from check_api_url import valid_api_url

class ApiUrlValidationTests(unittest.TestCase):
    def test_valid(self):
        self.assertTrue(valid_api_url('https://api.momento.ru'))
        self.assertTrue(valid_api_url('https://api.momento.ru:8443'))
    def test_invalid(self):
        for value in ('', 'http://api.momento.ru', 'https://localhost', 'https://127.0.0.1', 'https://example.com', 'https://api.example.invalid', 'https://api.momento.ru/?token=x', 'https://user:pass@api.momento.ru', 'https://10.0.0.1'):
            with self.subTest(value=value): self.assertFalse(valid_api_url(value))

if __name__ == '__main__': unittest.main()
