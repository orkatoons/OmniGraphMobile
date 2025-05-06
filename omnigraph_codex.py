import os
import sys
import numpy as np
import tempfile
import subprocess
import wave
import pyaudio
import io
import gc
from PIL import Image
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.image import Image as KivyImage
from kivy.uix.slider import Slider
from kivy.uix.spinner import Spinner
from kivy.uix.popup import Popup
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput
from kivy.graphics import Rectangle, Color
from kivy.graphics.texture import Texture
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.properties import BooleanProperty, NumericProperty, StringProperty, ObjectProperty
from kivy.uix.modalview import ModalView

# Function to get the correct resource path (works for both .py and .exe)
def resource_path(relative_path):
    """ Get absolute path to resource, works for dev & PyInstaller-compiled app """
    if hasattr(sys, '_MEIPASS'):
        return os.path.join(sys._MEIPASS, relative_path)  # When running as an .exe
    return os.path.join(os.path.abspath("."), relative_path)  # When running as .py

# Define the resource path dynamically
RESOURCE_PATH = resource_path("Resources")

class ReadMePopup(ModalView):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.size_hint = (0.8, 0.8)
        self.auto_dismiss = False
        
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        
        # Text content
        readme_text = """
Omnigraph Codex

Converts audio data into pixel values and vice-versa to generate unique images and reconstruct sound.

🔴 Method A – Stores different parts of the audio in Red, Green, and Blue channels sequentially.
🔵 Method B – Interweaves audio data into all channels in each pixel simultaneously.
🟢 Method C – Uses frequency spectrum analysis to distribute the audio across the image.

🎨 Why I Created This
Omnigraph Codex was designed for:
✅ Creating visually generative, abstract, and glitch art
✅ Sampling images into abstract audio pieces for music producers
✅ Exploring new forms of audiovisual transformation

✨ Tip: Different methods produce distinct visual and audio patterns! Experiment to discover new textures and sounds.

🌎 Join the Community!
I want this codex to bring together a community of artists and technical creators.
If you have any interesting creations, please share them at:

📌 r/OmnigraphCodex (Reddit)

Let's build something amazing together! 🎨🎶🚀
📌 Developed by Om Chari and GPT
"""
        
        scroll = ScrollView()
        text_input = TextInput(text=readme_text, readonly=True, size_hint_y=None)
        text_input.bind(minimum_height=text_input.setter('height'))
        scroll.add_widget(text_input)
        layout.add_widget(scroll)
        
        # OK button
        ok_btn = Button(text='OK', size_hint=(1, 0.1))
        ok_btn.bind(on_release=self.dismiss)
        layout.add_widget(ok_btn)
        
        self.add_widget(layout)

class AudioToImageConverter(BoxLayout):
    is_playing = BooleanProperty(False)
    current_position = NumericProperty(0)
    encoding_method = StringProperty("A")
    dark_mode = BooleanProperty(False)
    dragging_slider = BooleanProperty(False)
    
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.spacing = 10
        self.padding = 10
        
        # Initialize audio variables
        self.p = None
        self.stream = None
        self.audio_data = None
        self.encoded_image = None
        self.decoded_audio = None
        self.last_audio_file = None
        self.last_image_file = None
        self.last_operation = None
        self.output_type = None
        self.cursor_size = 5
        self.image_size = (0, 0)
        self.viz_rect_pos = (0, 0)
        
        try:
            self.p = pyaudio.PyAudio()
        except Exception as e:
            self.show_error("Audio Error", f"Failed to initialize audio: {str(e)}")
        
        # Setup UI
        self.setup_ui()
        
        # Timer for visualizer updates
        self.timer = Clock.schedule_interval(self.update_visualizer, 0.05)
        self.timer.cancel()  # Start paused
    
    def setup_ui(self):
        # Top bar layout
        top_bar = BoxLayout(size_hint=(1, None), height=40)
        
        # Read Me button
        self.read_me_btn = Button(text="Read Me", size_hint=(None, 1), width=80)
        self.read_me_btn.bind(on_press=self.show_read_me)
        top_bar.add_widget(self.read_me_btn)
        
        # Spacer
        top_bar.add_widget(Label(size_hint=(1, 1)))
        
        # Encoding method label
        top_bar.add_widget(Label(text="Encoding Method:"))
        
        # Encoding method dropdown
        self.method_combo = Spinner(
            text="A - Channel Multiplexing",
            values=[
                "A - Channel Multiplexing",
                "B - Pixel Interleaving", 
                "C - Spectral Encoding"
            ],
            size_hint=(None, 1),
            width=200
        )
        self.method_combo.bind(text=self.update_encoding_method)
        top_bar.add_widget(self.method_combo)
        
        # Dark mode button
        self.dark_mode_btn = Button(text="Toggle Dark Mode")
        self.dark_mode_btn.bind(on_press=self.toggle_dark_mode)
        top_bar.add_widget(self.dark_mode_btn)
        
        self.add_widget(top_bar)
        
        # Image display area
        self.image_widget = KivyImage(size_hint=(1, 1), allow_stretch=True)
        self.image_widget.bind(size=self.on_image_size_change)
        self.add_widget(self.image_widget)
        
        # Bottom controls
        controls = BoxLayout(size_hint=(1, None), height=40)
        
        self.info_label = Label(text="Ready", size_hint=(0.5, 1))
        controls.add_widget(self.info_label)
        
        # Single "Choose File" button
        self.choose_file_btn = Button(text="Choose File")
        self.choose_file_btn.bind(on_press=self.choose_file)
        controls.add_widget(self.choose_file_btn)
        
        self.play_btn = Button(text="Play")
        self.play_btn.bind(on_press=self.toggle_playback)
        controls.add_widget(self.play_btn)
        
        self.save_btn = Button(text="Save Output", disabled=True)
        self.save_btn.bind(on_press=self.save_output)
        controls.add_widget(self.save_btn)
        
        self.add_widget(controls)
        
        # Progress slider
        self.progress_slider = Slider(min=0, max=100, value=0, step=1)
        self.progress_slider.bind(
            on_touch_down=lambda x, y: setattr(self, 'dragging_slider', True),
            on_touch_up=self.slider_released
        )
        self.add_widget(self.progress_slider)
        
        # Visualization rectangle (drawn on canvas)
        with self.image_widget.canvas.after:
            Color(1, 0, 0, 0.8)
            self.viz_rect = Rectangle(pos=(0, 0), size=(self.cursor_size, self.cursor_size))
    
    def choose_file(self, instance):
        """Handle file selection for both audio and image files"""
        from plyer import filechooser
        file_path = filechooser.open_file(
            title="Choose Audio or Image File",
            filters=[("Audio Files", "*.wav;*.mp3;*.ogg;*.flac"), 
                    ("Image Files", "*.png;*.jpg;*.jpeg")]
        )
        
        if not file_path:
            return
            
        file_path = file_path[0]
        
        if file_path.lower().endswith(('.wav', '.mp3', '.ogg', '.flac')):
            self.encode_file(file_path=file_path)
        elif file_path.lower().endswith(('.png', '.jpg', '.jpeg')):
            self.decode_file(file_path=file_path)
        else:
            self.show_message("Unsupported file", "This file type is not supported")
    
    def on_image_size_change(self, instance, size):
        # Update visualization rectangle when image size changes
        self.image_size = size
    
    def show_read_me(self, instance):
        popup = ReadMePopup()
        popup.open()
    
    def toggle_dark_mode(self, instance):
        self.dark_mode = not self.dark_mode
        if self.dark_mode:
            Window.clearcolor = (0.1, 0.1, 0.1, 1)
            self.info_label.color = (1, 1, 1, 1)
        else:
            Window.clearcolor = (1, 1, 1, 1)
            self.info_label.color = (0, 0, 0, 1)
    
    def update_encoding_method(self, spinner, text):
        method_map = {
            "A - Channel Multiplexing": "A",
            "B - Pixel Interleaving": "B", 
            "C - Spectral Encoding": "C"
        }
        self.encoding_method = method_map.get(text, "A")
        
        # Reprocess stored file if available
        if self.last_operation == 'encode' and self.last_audio_file:
            self.encode_file(use_last=True)
        elif self.last_operation == 'decode' and self.last_image_file:
            self.decode_file(use_last=True)
    
    def encode_file(self, use_last=False, file_path=None):
        try:
            if file_path is not None:
                self.last_audio_file = file_path
                self.last_operation = 'encode'
            elif not use_last:
                from plyer import filechooser
                file_path = filechooser.open_file(
                    title="Open Audio File",
                    filters=[("Audio Files", "*.wav;*.mp3;*.ogg;*.flac")]
                )
                if not file_path:
                    return
                file_path = file_path[0]
                self.last_audio_file = file_path
                self.last_operation = 'encode'
            elif use_last:
                file_path = self.last_audio_file

            if file_path:
                self.encoded_image = self.encode_audio_to_image(file_path)
                if self.encoded_image is not None:
                    self.display_preview(self.encoded_image)
                    self.output_type = 'image'
                    self.save_btn.disabled = False
                    self.show_message("Success", "Encoding completed successfully!")
                    
                    # Auto-decode for playback
                    self.decoded_audio = self.decode_image_to_audio(self.encoded_image)
                    if self.decoded_audio is not None:
                        self.load_audio_for_playback()
        except Exception as e:
            self.show_error("Error", f"Encoding failed: {str(e)}")
    
    def decode_file(self, use_last=False, file_path=None):
        try:
            if file_path is not None:
                self.last_image_file = file_path
                self.last_operation = 'decode'
            elif not use_last:
                from plyer import filechooser
                file_path = filechooser.open_file(
                    title="Open Image File",
                    filters=[("Image Files", "*.png;*.jpg;*.jpeg")]
                )
                if not file_path:
                    return
                file_path = file_path[0]
                self.last_image_file = file_path
                self.last_operation = 'decode'
            elif use_last:
                file_path = self.last_image_file

            if file_path:
                self.decoded_audio = self.decode_image_to_audio(file_path)
                if self.decoded_audio is not None:
                    self.load_audio_for_playback()
                    self.display_preview(file_path)
                    self.output_type = 'audio'
                    self.save_btn.disabled = False
                    self.show_message("Success", "Decoding completed successfully!")
        except Exception as e:
            self.show_error("Error", f"Decoding failed: {str(e)}")
    
    def save_output(self, instance):
        try:
            from plyer import filechooser
            if self.output_type == 'image' and self.encoded_image is not None:
                file_path = filechooser.save_file(
                    title="Save Image",
                    filters=[("PNG Files", "*.png")]
                )
                if file_path:
                    file_path = file_path[0]
                    if not file_path.lower().endswith('.png'):
                        file_path += '.png'
                    self.encoded_image.save(file_path, "PNG")
                    self.show_message("Success", f"Image saved to {file_path}")
            elif self.output_type == 'audio' and self.decoded_audio is not None:
                file_path = filechooser.save_file(
                    title="Save Audio",
                    filters=[("WAV Files", "*.wav")]
                )
                if file_path:
                    file_path = file_path[0]
                    if not file_path.lower().endswith('.wav'):
                        file_path += '.wav'
                    with open(file_path, 'wb') as f:
                        f.write(self.decoded_audio.getvalue())
                    self.show_message("Success", f"Audio saved to {file_path}")
        except Exception as e:
            self.show_error("Error", f"Failed to save file: {str(e)}")
    
    def display_preview(self, image_data):
        if isinstance(image_data, Image.Image):
            # Convert PIL Image to Kivy texture
            image_data = image_data.convert("RGB")
            data = image_data.tobytes()
            texture = Texture.create(size=(image_data.width, image_data.height), colorfmt='rgb')
            texture.blit_buffer(data, colorfmt='rgb', bufferfmt='ubyte')
            self.image_widget.texture = texture
            self.image_size = (image_data.width, image_data.height)
        else:
            # Load directly from file path
            self.image_widget.source = image_data
            self.image_widget.reload()
            # Get actual image size after loading
            img = Image.open(image_data)
            self.image_size = img.size
    
    def slider_released(self, instance, touch):
        if touch.grab_current == instance:
            self.dragging_slider = False
            if self.audio_data:
                new_pos = int(self.progress_slider.value)
                self.current_position = new_pos
                if self.is_playing:
                    self.stop_playback()
                    self.start_playback()
    
    def toggle_playback(self, instance):
        if not hasattr(self, 'audio_data') or not self.audio_data:
            self.show_message("Playback", "No audio loaded or audio is empty!")
            return

        if self.is_playing:
            self.stop_playback()
        else:
            self.start_playback()
    
    def start_playback(self):
        if not hasattr(self, 'p') or self.p is None:
            self.show_error("Playback Error", "Audio system is not initialized.")
            return

        try:
            self.stream = self.p.open(
                format=self.p.get_format_from_width(2),
                channels=1,
                rate=44100,
                output=True,
                stream_callback=self.audio_callback
            )
            
            if self.stream is None:
                self.show_error("Playback Error", "Failed to create audio stream.")
                return

            self.is_playing = True
            self.play_btn.text = "Pause"
            self.timer()  # Start visualizer updates
        except Exception as e:
            self.show_error("Playback Error", f"Failed to start playback: {str(e)}")
    
    def stop_playback(self):
        self.timer.cancel()  # Stop visualizer updates
        self.is_playing = False
        self.play_btn.text = "Play"

        if hasattr(self, 'stream') and self.stream:
            self.stream.stop_stream()
            self.stream.close()
            self.stream = None
    
    def audio_callback(self, in_data, frame_count, time_info, status):
        try:
            if not hasattr(self, 'audio_data') or self.audio_data is None:
                return (b'\x00' * frame_count * 2, pyaudio.paComplete)
            
            total_samples = len(self.audio_data) // 2
            start = self.current_position
            end = min(start + frame_count, total_samples)
            
            if start >= total_samples:
                return (b'\x00' * frame_count * 2, pyaudio.paComplete)

            data = self.audio_data[start * 2:end * 2]
            self.current_position = end

            return (data, pyaudio.paContinue if end < total_samples else pyaudio.paComplete)

        except Exception as e:
            return (b'\x00' * frame_count * 2, pyaudio.paAbort)
    
    def update_visualizer(self, dt):
        if not hasattr(self, 'audio_data') or not self.audio_data or self.image_size[0] == 0:
            return

        if not self.dragging_slider:
            self.progress_slider.value = self.current_position

        total_samples = len(self.audio_data) // 2

        if self.encoding_method == "A":
            samples_per_channel = total_samples // 3
            channel_index = self.current_position // samples_per_channel
            channel_position = self.current_position % samples_per_channel
            
            current_pixel = channel_position
            row = current_pixel // self.image_size[0]
            col = current_pixel % self.image_size[0]

            if channel_index == 0:  # Red
                self.viz_rect_color = (1, 0, 0, 0.8)
            elif channel_index == 1:  # Green
                self.viz_rect_color = (0, 1, 0, 0.8)
            else:  # Blue
                self.viz_rect_color = (0, 0, 1, 0.8)

            if row < self.image_size[1]:
                self.viz_rect.pos = (col - self.cursor_size//2, row - self.cursor_size//2)

        elif self.encoding_method == "B":
            total_pixels = self.image_size[0] * self.image_size[1]
            total_steps = total_pixels * 3
            pixel_index = self.current_position // 3
            row = pixel_index // self.image_size[0]
            col = pixel_index % self.image_size[0]

            if row < self.image_size[1]:
                self.viz_rect.pos = (col - self.cursor_size//2, row - self.cursor_size//2)
                self.viz_rect_color = (0, 0, 0, 0.8)

        elif self.encoding_method == "C":
            current_pixel = self.current_position
            row = current_pixel // self.image_size[0]
            col = current_pixel % self.image_size[0]

            if row < self.image_size[1]:
                self.viz_rect.pos = (col - self.cursor_size//2, row - self.cursor_size//2)
                self.viz_rect_color = (0, 0, 0, 0.8)
        
        # Update visualization rectangle color
        with self.image_widget.canvas.after:
            Color(*self.viz_rect_color)
            self.viz_rect.pos = self.viz_rect.pos
            self.viz_rect.size = (self.cursor_size, self.cursor_size)
    
    def encode_audio_to_image(self, audio_path):
        temp_wav = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
        temp_wav.close()
        try:
            subprocess.run([
                "ffmpeg", "-y", "-i", audio_path,
                "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le",
                "-hide_banner", "-loglevel", "error", temp_wav.name
            ], check=True, stderr=subprocess.PIPE)
            with wave.open(temp_wav.name, 'rb') as wav_file:
                raw_data = wav_file.readframes(wav_file.getnframes())
            audio_data = np.frombuffer(raw_data, dtype=np.int16)
            audio_8bit = ((audio_data.astype(np.float32) + 32768) / 65535 * 255).astype(np.uint8)
            
            if self.encoding_method == "A":
                total = len(audio_8bit)
                split_points = [total // 3, 2 * total // 3]
                red = audio_8bit[:split_points[0]]
                green = audio_8bit[split_points[0]:split_points[1]]
                blue = audio_8bit[split_points[1]:]
                side = int(np.ceil(np.sqrt(len(red))))
                required = side ** 2
                red = np.pad(red, (0, required - len(red)), mode='constant')
                green = np.pad(green, (0, required - len(green)), mode='constant')
                blue = np.pad(blue, (0, required - len(blue)), mode='constant')
                rgb_array = np.zeros((side, side, 3), dtype=np.uint8)
                rgb_array[:, :, 0] = red.reshape((side, side))
                rgb_array[:, :, 1] = green.reshape((side, side))
                rgb_array[:, :, 2] = blue.reshape((side, side))
            
            elif self.encoding_method == "B":
                audio_8bit = audio_8bit[:len(audio_8bit) - (len(audio_8bit) % 3)]
                audio_8bit = audio_8bit.reshape(-1, 3)
                side = int(np.ceil(np.sqrt(len(audio_8bit))))
                required = side ** 2
                audio_8bit = np.pad(audio_8bit, ((0, required - len(audio_8bit)), (0, 0)), mode='constant')
                rgb_array = np.zeros((side, side, 3), dtype=np.uint8)
                rgb_array[:, :, 0] = audio_8bit[:, 0].reshape((side, side))
                rgb_array[:, :, 1] = audio_8bit[:, 2].reshape((side, side))
                rgb_array[:, :, 2] = audio_8bit[:, 1].reshape((side, side))
            
            elif self.encoding_method == "C":
                audio_float = audio_data.astype(np.float32) / 32768.0
                N = len(audio_float)
                Fs = 44100
                fft_data = np.fft.rfft(audio_float)
                low_cutoff = 1000
                mid_cutoff = 4000
                k_low = int(low_cutoff * N / Fs)
                k_mid = int(mid_cutoff * N / Fs)
                low_fft = fft_data.copy()
                low_fft[k_low:] = 0.0
                mid_fft = fft_data.copy()
                mid_fft[:k_low] = 0.0
                mid_fft[k_mid:] = 0.0
                high_fft = fft_data.copy()
                high_fft[:k_mid] = 0.0
                low_signal = np.fft.irfft(low_fft, n=N)
                mid_signal = np.fft.irfft(mid_fft, n=N)
                high_signal = np.fft.irfft(high_fft, n=N)
                
                def to_8bit(signal):
                    signal = (signal * 32768).astype(np.int16)
                    return ((signal.astype(np.float32) + 32768) / 65535 * 255).astype(np.uint8)
                
                low_8bit = to_8bit(low_signal)
                mid_8bit = to_8bit(mid_signal)
                high_8bit = to_8bit(high_signal)
                side = int(np.ceil(np.sqrt(N)))
                required = side ** 2
                red = np.pad(low_8bit, (0, required - N), mode='constant')
                green = np.pad(mid_8bit, (0, required - N), mode='constant')
                blue = np.pad(high_8bit, (0, required - N), mode='constant')
                rgb_array = np.zeros((side, side, 3), dtype=np.uint8)
                rgb_array[:, :, 0] = red.reshape((side, side))
                rgb_array[:, :, 1] = green.reshape((side, side))
                rgb_array[:, :, 2] = blue.reshape((side, side))
            
            img = Image.fromarray(rgb_array, 'RGB')
            return img
        
        except subprocess.CalledProcessError as e:
            self.show_error("Encoding Error", f"FFmpeg failed: {e.stderr.decode()}")
            return None
        except Exception as e:
            self.show_error("Error", f"Encoding failed: {str(e)}")
            return None
        finally:
            os.remove(temp_wav.name)
    
    def decode_image_to_audio(self, image_input):
        try:
            if isinstance(image_input, str):
                img = Image.open(image_input)
            else:
                img = image_input
            img = img.convert("RGB")
            rgb_array = np.array(img)

            if self.encoding_method == "A":
                red_channel = rgb_array[:, :, 0].flatten()
                green_channel = rgb_array[:, :, 1].flatten()
                blue_channel = rgb_array[:, :, 2].flatten()
                reconstructed_audio = np.concatenate([red_channel, green_channel, blue_channel])
                audio_16bit = ((reconstructed_audio.astype(np.float32) / 255) * 65535 - 32768).astype(np.int16)

            elif self.encoding_method == "B":
                red = rgb_array[:, :, 0].flatten()
                blue = rgb_array[:, :, 2].flatten()
                green = rgb_array[:, :, 1].flatten()
                audio_8bit = np.stack([red, blue, green], axis=-1).flatten()
                audio_16bit = ((audio_8bit.astype(np.float32) / 255) * 65535 - 32768).astype(np.int16)

            elif self.encoding_method == "C":
                red = rgb_array[:, :, 0].flatten()
                green = rgb_array[:, :, 1].flatten()
                blue = rgb_array[:, :, 2].flatten()
                red_16 = ((red.astype(np.float32) / 255) * 65535 - 32768).astype(np.int16)
                green_16 = ((green.astype(np.float32) / 255) * 65535 - 32768).astype(np.int16)
                blue_16 = ((blue.astype(np.float32) / 255) * 65535 - 32768).astype(np.int16)
                audio_16bit = (red_16 * 0.6 + green_16 * 0.3 + blue_16 * 0.1).astype(np.int16)

            # Save and return decoded audio
            audio_buffer = io.BytesIO()
            with wave.open(audio_buffer, 'wb') as wav_file:
                wav_file.setnchannels(1)
                wav_file.setsampwidth(2)
                wav_file.setframerate(44100)
                wav_file.writeframes(audio_16bit.tobytes())
            audio_buffer.seek(0)
            return audio_buffer

        except Exception as e:
            self.show_error("Error", f"Decoding failed: {str(e)}")
            return None
    
    def load_audio_for_playback(self):
        if self.decoded_audio:
            try:
                self.decoded_audio.seek(0)
                with wave.open(self.decoded_audio, 'rb') as wav_file:
                    self.audio_data = wav_file.readframes(wav_file.getnframes())
                self.progress_slider.max = len(self.audio_data) // 2
                self.current_position = 0
            except Exception as e:
                self.show_error("Error", f"Failed to load audio: {str(e)}")
    
    def show_message(self, title, message):
        content = BoxLayout(orientation='vertical', padding=10, spacing=10)
        content.add_widget(Label(text=message))
        btn = Button(text='OK', size_hint=(1, None), height=40)
        popup = Popup(title=title, content=content, size_hint=(0.8, 0.4))
        btn.bind(on_press=popup.dismiss)
        content.add_widget(btn)
        popup.open()
    
    def show_error(self, title, message):
        content = BoxLayout(orientation='vertical', padding=10, spacing=10)
        content.add_widget(Label(text=message))
        btn = Button(text='OK', size_hint=(1, None), height=40)
        popup = Popup(title=title, content=content, size_hint=(0.8, 0.4))
        btn.bind(on_press=popup.dismiss)
        content.add_widget(btn)
        popup.open()
    
    def on_stop(self):
        # Clean up resources when app closes
        try:
            if hasattr(self, 'stream') and self.stream:
                self.stream.stop_stream()
                self.stream.close()
            
            if hasattr(self, 'p') and self.p:
                self.p.terminate()
            
            if hasattr(self, 'timer'):
                self.timer.cancel()
            
            if hasattr(self, 'audio_data'):
                del self.audio_data
            
            gc.collect()
        except Exception as e:
            print(f"Error during close: {e}")

class OmnigraphCodexApp(App):
    def build(self):
        self.title = "Omnigraph Codex"
        return AudioToImageConverter()

if __name__ == "__main__":
    try:
        OmnigraphCodexApp().run()
    except Exception as e:
        print(f"Fatal error: {e}")
        sys.exit(1)